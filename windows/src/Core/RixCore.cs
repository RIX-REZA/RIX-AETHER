using System.Diagnostics;

namespace Rix.Windows.Core;

/// <summary>The five states the specification names, exactly.</summary>
public enum RixState
{
    Connecting,
    Connected,
    Degraded,
    Reconnecting,
    Disconnected,
}

/// <summary>
/// The supervisor's view of the connection, as one immutable value: the page
/// draws it, the tray reads it, and the tests assert on it.
/// </summary>
public sealed record CoreStatus
{
    public RixState State { get; init; } = RixState.Disconnected;
    public string Detail { get; init; } = "";
    public int Failures { get; init; }
    public int ReconnectAttempt { get; init; }
    public DateTime Since { get; init; } = DateTime.UtcNow;
    public ScanCandidate Best { get; init; }

    public bool IsConnected => State == RixState.Connected || State == RixState.Degraded;
}

/// <summary>
/// What a supervisor needs from one connection path. The real implementation
/// wraps <see cref="RixSession"/>; the tests substitute a scripted double.
///
/// Making this an interface is what keeps the state machine testable without a
/// child process, a network, or a registry.
/// </summary>
public interface IConnectionPath : IDisposable
{
    Task<bool> ConnectAsync(CancellationToken token);

    Task<bool> HealthAsync(CancellationToken token);

    Task DisconnectAsync();
}

/// <summary>
/// Exponential backoff with full jitter.
///
/// Full jitter -- a random value between zero and the exponential ceiling --
/// is what stops a machine that just woke from sleep from reconnecting in
/// lockstep with every other client. A floor keeps a healthy local reconnect
/// from being instant, and a ceiling keeps the app from waiting an hour.
/// </summary>
public sealed class BackoffPolicy
{
    private readonly Func<double> _random;

    public BackoffPolicy(TimeSpan? initial = null, TimeSpan? max = null, Func<double> random = null)
    {
        Initial = initial ?? TimeSpan.FromSeconds(1);
        Max = max ?? TimeSpan.FromSeconds(60);
        _random = random ?? (() => Random.Shared.NextDouble());
    }

    public TimeSpan Initial { get; }
    public TimeSpan Max { get; }

    public TimeSpan Next(int attempt)
    {
        var exponent = Math.Clamp(attempt - 1, 0, 16);
        var ceilingMs = Math.Min(Max.TotalMilliseconds,
            Initial.TotalMilliseconds * Math.Pow(2, exponent));
        var withJitter = ceilingMs * _random();
        return TimeSpan.FromMilliseconds(Math.Max(250, withJitter));
    }
}

public sealed class CoreSupervisorOptions
{
    /// <summary>How often a live path is asked whether it still carries traffic.</summary>
    public TimeSpan HealthInterval { get; set; } = TimeSpan.FromSeconds(15);

    /// <summary>A single failed check is noise; this many in a row is a problem.</summary>
    public int DegradedAfter { get; set; } = 2;

    /// <summary>This many consecutive failures triggers a reconnect.</summary>
    public int ReconnectAfter { get; set; } = 3;

    /// <summary>Bound on one connect attempt, so a hung core cannot wedge the state machine.</summary>
    public TimeSpan ConnectTimeout { get; set; } = TimeSpan.FromSeconds(90);

    /// <summary>Give up after this many consecutive failed reconnects.
    /// Zero or less means never give up: the supervisor keeps trying with the
    /// capped backoff until the user stops it. The resilience profile uses
    /// this, because a tunnel that abandoned the user would be the opposite
    /// of what it exists for.</summary>
    public int MaxReconnects { get; set; } = 8;

    /// <summary>The first reconnects wait this long at most, because a network
    /// flap usually heals in well under a second.</summary>
    public int FastRetryCount { get; set; }

    /// <summary>Bound on one direct pre-verification of the next peer.</summary>
    public TimeSpan VerifyTimeout { get; set; } = TimeSpan.FromSeconds(4);
}

/// <summary>
/// Rix Core: the layer between the Aether path and the UI.
///
/// It owns the lifecycle the specification asks for -- Connecting, Connected,
/// Degraded, Reconnecting, Disconnected -- and the rules around it: health
/// checks, backoff with jitter, network-change recovery and failover to the
/// best scanned address.
///
/// It does not itself speak MASQUE or WireGuard. It supervises an
/// <see cref="IConnectionPath"/>; the real path is <see cref="RixSession"/>.
/// That seam is deliberate: the state machine can be tested with no network,
/// and the transport can be replaced without rewriting the supervisor.
/// </summary>
public sealed class CoreSupervisor : IDisposable
{
    private readonly CoreSupervisorOptions _options;
    private readonly Func<CancellationToken, Task<IConnectionPath>> _createPath;
    private readonly BackoffPolicy _backoff;
    private readonly object _gate = new();

    private CancellationTokenSource _life;
    private IConnectionPath _path;
    private Task _loop;
    private bool _running;
    private bool _disposed;
    private int _failures;
    private int _reconnects;

    public CoreSupervisor(
        Func<CancellationToken, Task<IConnectionPath>> createPath,
        CoreSupervisorOptions options = null,
        BackoffPolicy backoff = null,
        Func<TimeSpan, CancellationToken, Task> delay = null)
    {
        _createPath = createPath ?? throw new ArgumentNullException(nameof(createPath));
        _options = options ?? new CoreSupervisorOptions();
        _backoff = backoff ?? new BackoffPolicy();
        Delay = delay ?? ((span, token) => Task.Delay(span, token));
        Status = new CoreStatus();
    }

    /// <summary>Injected so the tests do not sleep for real.</summary>
    public Func<TimeSpan, CancellationToken, Task> Delay { get; }

    public CoreStatus Status { get; private set; }

    public event Action<CoreStatus> Changed;
    public event Action<string> Log;

    /// <summary>Set by the host so a failover can propose the next address.
    /// Called once per attempt; the host's implementation is expected to
    /// rotate through the ranked candidates rather than always naming the
    /// same one.</summary>
    public Func<ScanCandidate> BestAddress { get; set; }

    /// <summary>
    /// Direct pre-verification of a proposed peer. Failover that hands an
    /// unverified address to the core trades one failed connection for
    /// another; this probe (TCP+TLS, straight out, never through the tunnel)
    /// costs a fraction of a core restart and is what makes the handover
    /// worth doing.
    /// </summary>
    public Func<ScanCandidate, Task<bool>> PreVerify { get; set; }

    /// <summary>Told when a proposed peer failed its direct probe, so the
    /// host can cool it down before the next pick.</summary>
    public Action<ScanCandidate> CandidateRejected { get; set; }

    /// <summary>Called after a path connects, so the host can apply proxy/routes.</summary>
    public Func<IConnectionPath, Task> PathReady { get; set; }

    public bool IsRunning => _running;

    public void Start()
    {
        lock (_gate)
        {
            if (_running || _disposed) return;
            _running = true;
            _reconnects = 0;
            _failures = 0;
            _life = new CancellationTokenSource();
            var token = _life.Token;
            _loop = RunAsync(token);
        }
    }

    public async Task StopAsync()
    {
        CancellationTokenSource life;
        Task loop;
        IConnectionPath path;
        lock (_gate)
        {
            if (!_running) return;
            _running = false;
            life = _life;
            loop = _loop;
            path = _path;
            _path = null;
            _life = null;
            _loop = null;
        }

        life?.Cancel();

        // Tell the path first, then wait: waiting first would leave the tunnel
        // and its proxy pointing at a core that is about to disappear.
        try { if (path != null) await path.DisconnectAsync(); }
        catch { }

        try { if (loop != null) await loop; }
        catch { }

        path?.Dispose();
        life?.Dispose();
        Set(RixState.Disconnected, "قطع شد / disconnected");
    }

    /// <summary>
    /// Windows moved: new Wi-Fi, cable pulled, resume from sleep. The path is
    /// almost certainly dead even if its socket has not noticed yet, so the
    /// state machine is kicked rather than waiting for a health check.
    /// </summary>
    public void NetworkChanged(string reason)
    {
        if (!_running) return;
        Log?.Invoke("network: " + reason);
        Interlocked.Exchange(ref _failures, _options.ReconnectAfter);
    }

    /// <summary>A health result from outside (the session's own egress probe).</summary>
    public void ReportHealth(bool ok)
    {
        if (!_running) return;
        if (ok)
        {
            Interlocked.Exchange(ref _failures, 0);
            if (Status.State == RixState.Degraded)
                Set(RixState.Connected, "اتصال پایدار است / healthy again");
            return;
        }

        var failures = Interlocked.Increment(ref _failures);
        if (failures >= _options.ReconnectAfter)
        {
            Log?.Invoke($"health: {failures} failures, reconnecting");
            return; // the loop sees the counter on its next tick
        }
        if (failures >= _options.DegradedAfter && Status.State == RixState.Connected)
            Set(RixState.Degraded, "کیفیت اتصال پایین است / connection degraded");
    }

    private async Task RunAsync(CancellationToken token)
    {
        try
        {
            while (!token.IsCancellationRequested)
            {
                // ---- bring the path up
                Set(RixState.Connecting, "اتصال به مسیر / starting the path");
                var path = await CreateAsync(token);
                if (path == null)
                {
                    if (!await WaitBeforeRetryAsync(token)) return;
                    continue;
                }

                _path = path;
                bool connected;
                try
                {
                    using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
                    timeout.CancelAfter(_options.ConnectTimeout);
                    connected = await path.ConnectAsync(timeout.Token);
                }
                catch (OperationCanceledException) when (token.IsCancellationRequested)
                {
                    return;
                }
                catch (Exception ex)
                {
                    Log?.Invoke("connect: " + ex.Message);
                    connected = false;
                }

                if (!connected)
                {
                    await DropPathAsync(path);
                    if (!await WaitBeforeRetryAsync(token)) return;
                    continue;
                }

                Interlocked.Exchange(ref _failures, 0);
                _reconnects = 0;
                Set(RixState.Connected, "متصل / connected");
                Log?.Invoke("core: connected");

                try { if (PathReady != null) await PathReady(path); }
                catch (Exception ex) { Log?.Invoke("ready: " + ex.Message); }

                // ---- watch it
                var reason = await WatchAsync(path, token);
                if (token.IsCancellationRequested) break;

                Log?.Invoke("core: " + reason);
                await DropPathAsync(path);

                if (!await WaitBeforeRetryAsync(token)) return;
            }
        }
        finally
        {
            Set(RixState.Disconnected, "قطع شد / disconnected");

            // The loop can end by itself -- giving up after MaxReconnects --
            // and it must leave Start() usable. Without this, _running stayed
            // true for ever and the connect button called Start(), which
            // returned because it believed the supervisor was still running.
            CancellationTokenSource life = null;
            lock (_gate)
            {
                _running = false;
                life = _life;
                _life = null;
                _loop = null;
            }
            if (life != null && !life.IsCancellationRequested)
            {
                try { life.Dispose(); } catch { }
            }
        }
    }

    /// <summary>
    /// Health checks until the path fails often enough to replace, or the
    /// caller cancels. Returns the reason it stopped watching.
    /// </summary>
    private async Task<string> WatchAsync(IConnectionPath path, CancellationToken token)
    {
        while (!token.IsCancellationRequested)
        {
            try
            {
                await Delay(_options.HealthInterval, token);
            }
            catch (OperationCanceledException)
            {
                return "stopped";
            }

            var failures = Volatile.Read(ref _failures);
            bool ok;
            try
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
                timeout.CancelAfter(TimeSpan.FromSeconds(20));
                ok = await path.HealthAsync(timeout.Token);
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                return "stopped";
            }
            catch
            {
                ok = false;
            }

            if (ok)
            {
                Interlocked.Exchange(ref _failures, 0);
                if (Status.State == RixState.Degraded)
                    Set(RixState.Connected, "اتصال پایدار است / healthy again");
                continue;
            }

            failures = Interlocked.Increment(ref _failures);
            if (failures >= _options.DegradedAfter && Status.State == RixState.Connected)
                Set(RixState.Degraded, "کیفیت اتصال پایین است / connection degraded");

            if (failures >= _options.ReconnectAfter)
                return "health checks failed";
        }
        return "stopped";
    }

    /// <summary>
    /// Creates the next path. Before it does, it asks the scanner for the best
    /// address; the path factory is what decides whether to use it.
    ///
    /// This is the failover seam. A path that can accept a peer uses
    /// <see cref="BestAddress"/> to build itself around the current winner.
    /// </summary>
    private async Task<IConnectionPath> CreateAsync(CancellationToken token)
    {
        try
        {
            await VerifyPeerAsync(token);
            return await _createPath(token);
        }
        catch (OperationCanceledException)
        {
            return null;
        }
        catch (Exception ex)
        {
            Log?.Invoke("path: " + ex.Message);
            return null;
        }
    }

    /// <summary>
    /// Probes the proposed peer before it is handed to the core. Up to three
    /// candidates are tried; each failure is reported back so the host rotates
    /// to the next one. When PreVerify is not wired, the pick is used as is --
    /// the scanner's own ranking is still better than nothing.
    /// </summary>
    private async Task VerifyPeerAsync(CancellationToken token)
    {
        for (var tries = 0; tries < 3; tries++)
        {
            var candidate = BestAddress?.Invoke();
            if (candidate == null || PreVerify == null)
            {
                if (candidate != null)
                    Log?.Invoke("failover: best edge " + candidate.Endpoint);
                return;
            }

            try
            {
                using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
                timeout.CancelAfter(_options.VerifyTimeout);
                if (await PreVerify(candidate))
                {
                    Log?.Invoke("failover: verified edge " + candidate.Endpoint);
                    return;
                }
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                return;
            }
            catch
            {
                // Treated the same as a failed probe below.
            }

            Log?.Invoke("failover: " + candidate.Endpoint + " did not answer directly, rotating");
            try { CandidateRejected?.Invoke(candidate); } catch { }
        }
    }

    private async Task DropPathAsync(IConnectionPath path)
    {
        try { await path.DisconnectAsync(); }
        catch { }
        try { path.Dispose(); }
        catch { }
        if (ReferenceEquals(_path, path)) _path = null;
    }

    /// <summary>Reconnecting pause; returns false when the caller must stop.</summary>
    private async Task<bool> WaitBeforeRetryAsync(CancellationToken token)
    {
        _reconnects++;
        if (_options.MaxReconnects > 0 && _reconnects > _options.MaxReconnects)
        {
            Set(RixState.Disconnected,
                "اتصال برقرار نشد / could not connect after " + _options.MaxReconnects + " attempts");
            Log?.Invoke("core: giving up after " + _options.MaxReconnects + " attempts");
            try { await Delay(TimeSpan.FromSeconds(2), token); } catch { }
            // Stay alive: the user may press connect again, which calls Start
            // on a fresh supervisor. This loop parks until then.
            return false;
        }

        // A network flap usually heals in well under a second, so the first
        // couple of retries are deliberately quick; after that the exponential
        // backoff takes over and stays capped.
        var wait = _reconnects <= _options.FastRetryCount
            ? Resilience.FastRetryMin + TimeSpan.FromMilliseconds(
                Random.Shared.NextDouble() *
                (Resilience.FastRetryMax - Resilience.FastRetryMin).TotalMilliseconds)
            : _backoff.Next(_reconnects);
        Set(RixState.Reconnecting,
            $"تلاش دوباره تا {wait.TotalSeconds:0.0} ثانیه / reconnecting");
        Log?.Invoke($"core: backoff {wait.TotalMilliseconds:0}ms (attempt {_reconnects})");

        try
        {
            await Delay(wait, token);
            return !token.IsCancellationRequested;
        }
        catch (OperationCanceledException)
        {
            return false;
        }
    }

    private void Set(RixState state, string detail)
    {
        var previous = Status;
        if (previous.State == state && previous.Detail == detail && state != RixState.Connected)
            return;

        Status = new CoreStatus
        {
            State = state,
            Detail = detail,
            Failures = Volatile.Read(ref _failures),
            ReconnectAttempt = _reconnects,
            Since = previous.State == state ? previous.Since : DateTime.UtcNow,
            Best = BestAddress?.Invoke(),
        };
        Changed?.Invoke(Status);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        try { StopAsync().GetAwaiter().GetResult(); } catch { }
    }
}

#if !RIX_CORE_TEST
/// <summary>
/// The real connection path: one <see cref="RixSession"/>.
///
/// Kept thin on purpose. The session already knows how to walk a transport
/// chain and prove the tunnel with a request; the supervisor adds the health
/// loop and state names around it.
/// </summary>
public sealed class SessionConnectionPath : IConnectionPath
{
    private readonly RixSession _session;
    private readonly Func<RixOptions> _current;
    private readonly bool _useSystemProxy;

    public SessionConnectionPath(RixSession session, Func<RixOptions> current, bool useSystemProxy = true)
    {
        _session = session;
        _current = current;
        _useSystemProxy = useSystemProxy;
    }

    public async Task<bool> ConnectAsync(CancellationToken token)
    {
        // The session owns its own cancellation and proxy lifecycle, so this
        // waits on its task rather than passing the token in. That is what
        // makes the supervisor's ConnectTimeout real: when the wait expires,
        // the session is cancelled and torn down instead of being left running
        // behind a state machine that has already moved on.
        var connect = _session.ConnectAsync(_current(), useSystemProxy: _useSystemProxy);
        try
        {
            await connect.WaitAsync(token);
        }
        catch (OperationCanceledException)
        {
            try { await _session.DisconnectAsync(silent: true); } catch { }
            throw;
        }

        return _session.Current.Phase == Phase.Running;
    }

    public async Task<bool> HealthAsync(CancellationToken token)
    {
        var port = _session.Current.Port;
        if (port <= 0) return false;
        var egress = await Probe.ThroughSocksAsync(port, 10000, token);
        return egress != null && !string.IsNullOrEmpty(egress.Ip);
    }

    public Task DisconnectAsync() => _session.DisconnectAsync();

    public void Dispose()
    {
        // The host owns the session's lifetime: disposing it here would break
        // the manual connect button that created it.
    }
}
#endif
