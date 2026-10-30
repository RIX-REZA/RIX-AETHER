namespace Rix.Windows.Core;

public enum Phase { Idle, Starting, Connecting, Running, Stopping, Error }

/// <summary>Everything the window draws, in one immutable value.</summary>
public sealed record Snapshot
{
    public Phase Phase { get; init; } = Phase.Idle;
    public string Message { get; init; } = "";
    public string Transport { get; init; } = "";
    public string TransportLabel { get; init; } = "";
    public int Port { get; init; }
    public bool ProxyOn { get; init; }
    public Egress Egress { get; init; }
    public Egress Local { get; init; }
    public long Up { get; init; }
    public long Down { get; init; }
    public TimeSpan Uptime { get; init; }
    public bool Psiphon { get; init; }
    public string PsiphonRegion { get; init; } = "";
}

/// <summary>
/// One session: find a transport that works, carry the machine over it, and
/// keep saying what is actually true while it does.
///
/// The shape follows the phone, because the phone's version was corrected
/// against real networks: try the remembered transport first, then the ones most
/// likely to survive a filtered network, and never call the tunnel "connected"
/// until a real request has come back through it. A SOCKS listener that accepts
/// is a listener, not a tunnel.
/// </summary>
public sealed class RixSession : IDisposable
{
    // Dialling the far end can take a while on a filtered network, and the
    // first attempt pays for a scan.
    private const int FirstProbeMs = 20_000;
    private const int NextProbeMs = 10_000;
    private const int ListenMs = 15_000;

    // Psiphon also fetches a server list and picks an entry, which is minutes
    // rather than seconds on a bad line.
    private const int PsiphonProbeMs = 60_000;

    /// <summary>How often the exit address is asked for again once running.</summary>
    private static readonly TimeSpan EgressRefresh = TimeSpan.FromSeconds(90);

    private static readonly string[] Ranked =
    {
        Transports.MasqueH2,
        Transports.Gool,
        Transports.Mim,
        Transports.MasqueH3,
        Transports.GoolClassic,
        Transports.WireGuard,
    };

    private readonly AetherProcess _core = new();
    private readonly object _gate = new();
    private CancellationTokenSource _session;
    private Snapshot _snapshot = new();
    private string _remembered;

    public event Action<Snapshot> Changed;
    public event Action<string> Log;

    public Snapshot Current => _snapshot;

    public RixSession()
    {
        _core.LogLine += line => Log?.Invoke(line);
        _core.StatsChanged += stats => Publish(_snapshot with
        {
            Up = stats.Up,
            Down = stats.Down,
            Uptime = stats.Uptime,
        });
        _core.Exited += OnCoreExited;
    }

    /// <summary>
    /// The remembered transport, kept beside the settings rather than in the
    /// registry: it is a hint about this machine's network, nothing more.
    /// </summary>
    public string Remembered
    {
        get => _remembered;
        set => _remembered = value;
    }

    // ------------------------------------------------------------------ public

    public async Task ConnectAsync(RixOptions options, bool useSystemProxy)
    {
        await DisconnectAsync(silent: true);

        var cts = new CancellationTokenSource();
        lock (_gate) _session = cts;

        try
        {
            Publish(new Snapshot
            {
                Phase = Phase.Starting,
                Message = "آماده‌سازی هسته / preparing the core",
                Psiphon = options.Psiphon,
                PsiphonRegion = options.PsiphonRegion,
            });

            var exe = AetherProcess.Locate();
            if (exe == null)
            {
                Fail("فایل هسته پیدا نشد / the core was not found beside the app (core\\aether.exe)");
                return;
            }

            Directory.CreateDirectory(AppState.Dir);
            var working = Path.GetDirectoryName(exe);

            var chain = Chain(options).ToList();
            for (var index = 0; index < chain.Count; index++)
            {
                var candidate = chain[index];
                if (cts.IsCancellationRequested)
                {
                    // A stop can land between starting a core and getting here.
                    // DisconnectAsync stopped what was running then; this is for
                    // the core this method started afterwards.
                    await Task.Run(() => _core.Stop());
                    return;
                }

                if (index > 0)
                {
                    Log?.Invoke($"chain: {Transports.Label(candidate.Transport, false)} بعد از تلاش ناموفق");
                    _core.Stop();
                    await Task.Delay(250, cts.Token);
                }

                Publish(_snapshot with
                {
                    Phase = Phase.Connecting,
                    Transport = candidate.Transport,
                    TransportLabel = Transports.Label(candidate.Transport, false),
                    Message = $"تلاش {index + 1}/{chain.Count}: {Transports.Label(candidate.Transport, false)}",
                    Psiphon = candidate.Psiphon,
                    PsiphonRegion = candidate.PsiphonRegion,
                });

                var args = candidate.ToArgs(AppState.ConfigPath);
                if (!_core.Start(exe, args, working))
                {
                    Fail("هسته اجرا نشد / the core could not be started");
                    return;
                }

                if (!await WaitForListenerAsync(candidate.SocksPort, ListenMs, cts.Token)) continue;

                if (candidate.Psiphon)
                {
                    Publish(_snapshot with { Message = "در حال اتصال به سایفون / bringing Psiphon up" });
                }

                var budget = candidate.Psiphon ? PsiphonProbeMs : index == 0 ? FirstProbeMs : NextProbeMs;
                var egress = await Probe.ThroughSocksAsync(candidate.SocksPort, budget, cts.Token);
                if (egress == null)
                {
                    Log?.Invoke("probe: هیچ ترافیکی از تونل برنگشت / nothing came back through the tunnel");
                    continue;
                }

                if (cts.IsCancellationRequested) return;

                Remembered = candidate.Transport;

                var proxyOn = false;
                if (useSystemProxy)
                {
                    proxyOn = SystemProxy.Enable(candidate.ProxyPort, out var error);
                    if (!proxyOn) Log?.Invoke("proxy: " + error);
                }

                Publish(_snapshot with
                {
                    Phase = Phase.Running,
                    Message = "متصل / connected",
                    Port = candidate.SocksPort,
                    ProxyOn = proxyOn,
                    Egress = egress,
                    Transport = candidate.Transport,
                    TransportLabel = Transports.Label(candidate.Transport, false),
                    Up = 0,
                    Down = 0,
                    Uptime = TimeSpan.Zero,
                });

                _ = WatchAsync(cts.Token);
                return;
            }

            // The last attempt's core is still up. Left alone it keeps a
            // tunnel running that the window has already declared failed, and
            // it holds the port the next attempt will want.
            await Task.Run(() => _core.Stop());
            Fail("هیچ پروتکلی جواب نداد / no transport carried traffic. The network may be blocking all of them.");
        }
        catch (OperationCanceledException)
        {
            // Stopped by the user; DisconnectAsync has already published Idle.
        }
        catch (Exception ex)
        {
            await Task.Run(() => _core.Stop());
            Fail(ex.Message);
        }
    }

    public async Task DisconnectAsync(bool silent = false)
    {
        CancellationTokenSource cts;
        lock (_gate)
        {
            cts = _session;
            _session = null;
        }
        cts?.Cancel();
        cts?.Dispose();

        if (!silent)
        {
            Publish(_snapshot with { Phase = Phase.Stopping, Message = "در حال قطع / disconnecting" });
        }

        // Proxy first: the moment the core is gone, a machine still pointing at
        // it has no working browser, and that gap is what a user notices.
        SystemProxy.Disable();

        await Task.Run(() => _core.Stop());

        if (!silent)
        {
            Publish(new Snapshot { Phase = Phase.Idle, Message = "قطع شد / disconnected" });
        }
    }

    // ------------------------------------------------------------------- chain

    /// <summary>
    /// Up to three transports: the one asked for, then the ones that survive a
    /// filtered network, and no repeats.
    /// </summary>
    private IEnumerable<RixOptions> Chain(RixOptions options)
    {
        var chain = new List<RixOptions> { options };
        if (!options.AutoFallback) return chain;

        var ranked = new List<string>();
        if (!string.IsNullOrEmpty(Remembered)) ranked.Add(Remembered);
        ranked.AddRange(Ranked);

        foreach (var transport in ranked)
        {
            if (chain.Count >= 3) break;
            if (chain.Any(c => c.Transport == transport)) continue;
            // Fragmenting the first bytes of the handshake is what gets a
            // MASQUE/H2 connect past a middlebox that matches on them.
            chain.Add(options with { Transport = transport, Fragment = true });
        }
        return chain;
    }

    // ------------------------------------------------------------------ watchers

    /// <summary>
    /// While running: notice the core dying, and ask the exit address again now
    /// and then. A tunnel that changes address without saying so is worth
    /// catching, because everything the user sees about their location is that
    /// address.
    /// </summary>
    private async Task WatchAsync(CancellationToken token)
    {
        var nextEgress = DateTime.UtcNow + EgressRefresh;
        try
        {
            while (!token.IsCancellationRequested)
            {
                await Task.Delay(2000, token);
                if (!_core.IsRunning)
                {
                    // The Exited handler has already published the reason.
                    return;
                }

                if (DateTime.UtcNow >= nextEgress)
                {
                    nextEgress = DateTime.UtcNow + EgressRefresh;
                    var port = _snapshot.Port;
                    if (port > 0)
                    {
                        var egress = await Probe.ThroughSocksAsync(port, 8000, token);
                        if (egress != null && egress != _snapshot.Egress)
                        {
                            Log?.Invoke("exit: " + egress);
                            Publish(_snapshot with { Egress = egress });
                        }
                    }
                }
            }
        }
        catch (OperationCanceledException)
        {
        }
    }

    private void OnCoreExited(int code)
    {
        lock (_gate)
        {
            if (_session == null) return;   // a stop the user asked for
            _session.Cancel();
            _session = null;
        }

        SystemProxy.Disable();

        var detail = _core.Tail.LastOrDefault(l => l.Contains("core: exited"));
        Fail("هسته متوقف شد / the core stopped" + (detail == null ? "" : " — " + detail));
    }

    /// <summary>
    /// Waits until the SOCKS5 listener accepts. Cheap and honest: it says the
    /// core is up, not that the tunnel works -- the probe decides that.
    /// </summary>
    private static async Task<bool> WaitForListenerAsync(int port, int timeoutMs, CancellationToken token)
    {
        var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (DateTime.UtcNow < deadline)
        {
            if (token.IsCancellationRequested) return false;
            try
            {
                using var client = new System.Net.Sockets.TcpClient();
                await client.ConnectAsync("127.0.0.1", port, token);
                return true;
            }
            catch (OperationCanceledException)
            {
                return false;
            }
            catch
            {
                await Task.Delay(250, token);
            }
        }
        return false;
    }

    /// <summary>Asks the direct route what it sees, for the comparison line.</summary>
    public async Task<Egress> LocalAsync()
    {
        try
        {
            var local = await Probe.DirectAsync(6000, CancellationToken.None);
            if (local != null) Publish(_snapshot with { Local = local });
            return local;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>Asks again right now, from the button in the UI.</summary>
    public async Task<Egress> RefreshEgressAsync()
    {
        var port = _snapshot.Port;
        if (port <= 0) return null;
        try
        {
            var egress = await Probe.ThroughSocksAsync(port, 10_000, CancellationToken.None);
            if (egress != null) Publish(_snapshot with { Egress = egress });
            return egress;
        }
        catch
        {
            return null;
        }
    }

    private void Fail(string message)
    {
        // The session is over. Leaving the token in place would make a later
        // core exit look like an unexpected crash of a session that is not
        // there -- and would let a second connect try to cancel it twice.
        CancellationTokenSource cts;
        lock (_gate)
        {
            cts = _session;
            _session = null;
        }
        try { cts?.Cancel(); } catch { }
        cts?.Dispose();

        Log?.Invoke("error: " + message);
        Publish(_snapshot with { Phase = Phase.Error, Message = message, ProxyOn = false });
    }

    private void Publish(Snapshot next)
    {
        _snapshot = next;
        Changed?.Invoke(next);
    }

    public void Dispose()
    {
        CancellationTokenSource cts;
        lock (_gate) { cts = _session; _session = null; }
        cts?.Cancel();
        cts?.Dispose();
        SystemProxy.Disable();
        _core.Dispose();
        GC.SuppressFinalize(this);
    }
}
