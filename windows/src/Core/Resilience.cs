using System.Diagnostics;

namespace Rix.Windows.Core;

/// <summary>
/// The resilience layer: the parts of "stay connected" that are not the tunnel
/// itself.
///
/// Three ideas, all plain networking:
///
///  1. Never pin one address. A single hard-coded peer is a single point of
///     failure, so candidates rotate out of the scanner's ranked list and a
///     peer that just failed is cooled down before it is offered again.
///  2. Verify before you hand over. A direct TCP+TLS probe of the next peer
///     costs a fraction of a core restart, so the supervisor's failover only
///     leaves a candidate when that candidate has already answered.
///  3. React to the machine, not just the socket. Address changes, interface
///     flaps and resume-from-sleep are known causes of a dead tunnel that a
///     healthy-looking TCP socket hides, so they kick the supervisor instead
///     of waiting for a health check to notice.
/// </summary>

/// <summary>
/// Rotation over the scanner's ranked candidates.
///
/// The rules: highest score wins among peers that are not cooling down, and a
/// peer that just failed gets an exponentially growing cooldown so a dead edge
/// is not retried every second. Successes clear the debt. When everything is
/// cooling down, the pool still answers -- with the least-bad candidate --
/// because refusing to answer would stall reconnects entirely.
/// </summary>
public sealed class PeerPool
{
    private sealed record Entry(ScanCandidate Candidate, int Failures, DateTime FailedAt)
    {
        public Entry With(ScanCandidate candidate, int failures, DateTime failedAt) =>
            new(candidate, failures, failedAt);
    }

    private readonly object _gate = new();
    private readonly Func<DateTime> _now;
    private readonly TimeSpan _baseCooldown;
    private readonly TimeSpan _maxCooldown;

    private List<Entry> _ranked = new();
    private string _lastPicked = "";

    public PeerPool(
        Func<DateTime> now = null,
        TimeSpan? baseCooldown = null,
        TimeSpan? maxCooldown = null)
    {
        _now = now ?? (() => DateTime.UtcNow);
        _baseCooldown = baseCooldown ?? TimeSpan.FromSeconds(30);
        _maxCooldown = maxCooldown ?? TimeSpan.FromMinutes(10);
    }

    /// <summary>Replaces the candidate list, keeping failure state per endpoint.</summary>
    public void Replace(IReadOnlyList<ScanCandidate> ranked)
    {
        lock (_gate)
        {
            var old = _ranked.ToDictionary(e => e.Candidate.Endpoint, e => e);
            _ranked = ranked
                .Where(c => c != null && !string.IsNullOrEmpty(c.Endpoint))
                .Select(c => old.TryGetValue(c.Endpoint, out var entry)
                    ? entry.With(c, entry.Failures, entry.FailedAt)
                    : new Entry(c, 0, DateTime.MinValue))
                .ToList();
        }
    }

    public int Count
    {
        get { lock (_gate) return _ranked.Count; }
    }

    /// <summary>
    /// The next peer to try: eligible (not cooling down) with the best
    /// score, rotated so consecutive calls do not return the same endpoint.
    /// </summary>
    public ScanCandidate Pick()
    {
        lock (_gate)
        {
            var now = _now();
            var eligible = _ranked
                .Where(e => e.Failures == 0 || now - e.FailedAt >= Cooldown(e.Failures))
                .OrderByDescending(e => e.Candidate.Score)
                .ThenBy(e => e.Failures)
                .ThenBy(e => e.Candidate.Endpoint == _lastPicked ? 1 : 0)
                .ThenBy(e => e.Candidate.Endpoint, StringComparer.Ordinal)
                .ToList();

            var chosen = eligible.Count > 0
                ? eligible[0]
                : // Everything is cooling down. Offer the highest-scored peer
                  // anyway: a cooldown is a preference, not a veto, and the
                  // supervisor's own connect timeout still bounds the cost.
                _ranked.OrderByDescending(e => e.Candidate.Score).FirstOrDefault();

            if (chosen == null) return null;
            _lastPicked = chosen.Candidate.Endpoint;
            return chosen.Candidate;
        }
    }

    /// <summary>Restores the rotation point from the previous run. Without it
    /// every launch would reopen the session on the same top-ranked edge,
    /// because the persisted ranking does not change between runs. The peer
    /// the last session used is cooled down for one base period, so a fresh
    /// launch rotates to the next candidate instead.</summary>
    public void SeedLastUsed(string endpoint)
    {
        if (string.IsNullOrEmpty(endpoint)) return;
        lock (_gate)
        {
            _lastPicked = endpoint;
            // The same mechanism a failure uses, for the same reason: this
            // peer was just tried. It is a preference for one base cooldown,
            // not a verdict about the peer's quality.
            var index = _ranked.FindIndex(e => e.Candidate.Endpoint == endpoint);
            if (index >= 0)
                _ranked[index] = _ranked[index].With(_ranked[index].Candidate, 1, _now());
        }
    }

    /// <summary>The peer this pool named last, so the host can persist the
    /// rotation point across launches.</summary>
    public string LastPicked
    {
        get { lock (_gate) return _lastPicked; }
    }

    /// <summary>Records that an attempt against this peer failed.</summary>
    public void ReportFailure(string endpoint)
    {
        if (string.IsNullOrEmpty(endpoint)) return;
        lock (_gate)
        {
            var index = _ranked.FindIndex(e => e.Candidate.Endpoint == endpoint);
            if (index < 0) return;
            var entry = _ranked[index];
            _ranked[index] = entry.With(entry.Candidate, entry.Failures + 1, _now());
        }
    }

    /// <summary>Records that this peer carried traffic, clearing its debt.</summary>
    public void ReportSuccess(string endpoint)
    {
        if (string.IsNullOrEmpty(endpoint)) return;
        lock (_gate)
        {
            var index = _ranked.FindIndex(e => e.Candidate.Endpoint == endpoint);
            if (index < 0) return;
            var entry = _ranked[index];
            _ranked[index] = entry.With(entry.Candidate, 0, DateTime.MinValue);
        }
    }

    /// <summary>Base cooldown for the first failure, then doubling per
    /// additional failure: 30s, 60s, 120s, … capped at the maximum.</summary>
    private TimeSpan Cooldown(int failures) =>
        failures <= 1 ? _baseCooldown
        : TimeSpan.FromTicks(Math.Min(
            _maxCooldown.Ticks,
            _baseCooldown.Ticks * (long)Math.Pow(2, Math.Min(failures - 1, 12))));
}

#if !RIX_CORE_TEST
/// <summary>
/// Network-conditions watcher. Windows raises these on address changes, on an
/// interface coming or going, and (via SystemEvents) on resume from sleep --
/// the three moments a running tunnel is most likely dead without its socket
/// admitting it. Events are debounced: a DHCP renew can burst a dozen address
/// events, and the supervisor only needs one kick.
/// </summary>
public sealed class NetworkWatch : IDisposable
{
    private readonly Action<string> _onChanged;
    private readonly object _gate = new();
    private System.Timers.Timer _debounce;
    private string _pending;
    private bool _disposed;

    public NetworkWatch(Action<string> onChanged)
    {
        _onChanged = onChanged;
        _debounce = new System.Timers.Timer(1500) { AutoReset = false };
        _debounce.Elapsed += (_, _) => Fire();

        System.Net.NetworkInformation.NetworkChange.NetworkAddressChanged += OnAny;
        System.Net.NetworkInformation.NetworkChange.NetworkAvailabilityChanged += OnAny;
        Microsoft.Win32.SystemEvents.PowerModeChanged += OnPower;
    }

    private void OnAny(object sender, EventArgs e) => Kick("network: addresses changed");

    private void OnPower(object sender, Microsoft.Win32.PowerModeChangedEventArgs e)
    {
        if (e.Mode == Microsoft.Win32.PowerModes.Resume)
            Kick("network: resumed from sleep");
    }

    private void Kick(string reason)
    {
        lock (_gate)
        {
            if (_disposed) return;
            _pending = reason;
            try { _debounce.Stop(); _debounce.Start(); } catch { }
        }
    }

    private void Fire()
    {
        string reason;
        lock (_gate)
        {
            if (_disposed) return;
            reason = _pending;
            _pending = null;
        }
        if (reason != null)
        {
            try { _onChanged(reason); } catch { }
        }
    }

    public void Dispose()
    {
        lock (_gate)
        {
            if (_disposed) return;
            _disposed = true;
            try { _debounce?.Dispose(); } catch { }
            _debounce = null;
        }
        System.Net.NetworkInformation.NetworkChange.NetworkAddressChanged -= OnAny;
        System.Net.NetworkInformation.NetworkChange.NetworkAvailabilityChanged -= OnAny;
        Microsoft.Win32.SystemEvents.PowerModeChanged -= OnPower;
    }
}
#endif

/// <summary>
/// Resilience tuning for the supervisor.
///
/// Fast retries first: a network flap usually heals in well under a second,
/// so the first two reconnects wait only a few hundred milliseconds. After
/// that the exponential backoff takes over. MaxReconnects = 0 means the
/// supervisor never gives up on its own -- it keeps trying, with a capped
/// delay, until the user stops it.
/// </summary>
public static class Resilience
{
    public const int UnlimitedReconnects = 0;
    public const int FastRetries = 2;
    public static readonly TimeSpan FastRetryMin = TimeSpan.FromMilliseconds(400);
    public static readonly TimeSpan FastRetryMax = TimeSpan.FromMilliseconds(900);

    public static CoreSupervisorOptions Options() => new()
    {
        HealthInterval = TimeSpan.FromSeconds(15),
        DegradedAfter = 2,
        ReconnectAfter = 3,
        ConnectTimeout = TimeSpan.FromSeconds(90),
        MaxReconnects = UnlimitedReconnects,
        FastRetryCount = FastRetries,
    };
}
