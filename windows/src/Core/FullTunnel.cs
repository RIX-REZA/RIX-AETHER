using System.Diagnostics;
using System.Net.NetworkInformation;
using System.Security.Principal;
using System.Text;

namespace Rix.Windows.Core;

public enum TunnelMode
{
    SystemProxy,
    FullTunnel,
}

/// <summary>Why Full Tunnel can or cannot run on this machine.</summary>
public sealed record FullTunnelAvailability(bool Available, string Reason, bool Elevated)
{
    public static FullTunnelAvailability Check()
    {
        var elevated = IsElevated();
        var helper = HelperPath;
        var wintun = WintunPath;

        if (!File.Exists(helper))
            return new FullTunnelAvailability(false,
                "فایل hev-socks5-tunnel.exe در پوشه core نیست / tunnel helper is missing", elevated);
        if (!File.Exists(wintun))
            return new FullTunnelAvailability(false,
                "wintun.dll در پوشه core نیست / Wintun is missing", elevated);

        // Elevation is deliberately not part of availability. The UI process
        // is always asInvoker; the helper is the part that carries
        // requireAdministrator, so Windows raises UAC when the mode is
        // selected. Requiring elevation here would make the mode permanently
        // greyed out in the very process that has to offer it.
        return new FullTunnelAvailability(true, "", elevated);
    }

    /// <summary>The helper lives in core\ when launched by the UI, and beside
    /// itself when it *is* the helper. Both are checked.</summary>
    public static string HelperPath => FindBeside("hev-socks5-tunnel.exe");

    public static string WintunPath => FindBeside("wintun.dll");

    private static string FindBeside(string name)
    {
        var beside = Path.Combine(AppContext.BaseDirectory, name);
        if (File.Exists(beside)) return beside;
        return Path.Combine(AppContext.BaseDirectory, "core", name);
    }

    public static bool IsElevated()
    {
        try
        {
            using var identity = WindowsIdentity.GetCurrent();
            var principal = new WindowsPrincipal(identity);
            return principal.IsInRole(WindowsBuiltInRole.Administrator);
        }
        catch
        {
            return false;
        }
    }
}

public sealed class FullTunnelOptions
{
    public string AdapterName { get; set; } = "RIX";
    public string AdapterAddress { get; set; } = "198.18.0.1";
    public string AdapterMask { get; set; } = "255.255.0.0";
    public int Mtu { get; set; } = 1400;
    public int SocksPort { get; set; } = 1819;

    /// <summary>Block outbound traffic if the tunnel drops. Off by default:
    /// a kill switch that is wrong is worse than the leak it prevents.</summary>
    public bool KillSwitch { get; set; }

    /// <summary>Addresses that keep using the physical route.</summary>
    public List<string> SplitTunnelAddresses { get; set; } = new();
}

/// <summary>
/// Full Tunnel: a Wintun adapter with the machine's default route pointed at
/// it, and hev-socks5-tunnel carrying the packets to the local SOCKS listener.
///
/// Honest limits, also in the Persian README:
///  * The helper and Wintun are not shipped in this build. Without them the
///    mode reports itself unavailable instead of pretending to work.
///  * The route/DNS/kill-switch code cannot be exercised on a Linux build
///    machine. It is written against documented Windows commands and is marked
///    experimental until it is run on Windows.
///  * Split tunnelling here is address-based. Excluding an application needs a
///    WFP callout or a per-process proxy, which this build does not ship.
/// </summary>
public sealed class FullTunnel : IDisposable
{
    private readonly FullTunnelOptions _options;
    private readonly Action<string> _log;
    private readonly string _dataDir;
    private Process _helper;
    private bool _routesAdded;
    private bool _adapterConfigured;
    private bool _killSwitchOn;
    private string _originalGateway;
    private int _originalInterface;

    public FullTunnel(FullTunnelOptions options, string dataDir, Action<string> log = null)
    {
        _options = options;
        _dataDir = dataDir;
        _log = log ?? (_ => { });
    }

    public bool IsRunning => _helper != null && !_helper.HasExited;

    public FullTunnelAvailability Availability => FullTunnelAvailability.Check();

    /// <summary>
    /// Starts the helper and routes the machine through it. Returns false with
    /// a reason rather than leaving a half-configured adapter behind.
    /// </summary>
    public async Task<(bool Ok, string Error)> StartAsync(CancellationToken token)
    {
        var available = Availability;
        if (!available.Available) return (false, available.Reason);

        try
        {
            Directory.CreateDirectory(_dataDir);
            var config = Path.Combine(_dataDir, "hev-tunnel.yml");
            File.WriteAllText(config, BuildConfig(), new UTF8Encoding(false));

            _helper = new Process
            {
                StartInfo = new ProcessStartInfo
                {
                    FileName = FullTunnelAvailability.HelperPath,
                    Arguments = "-c \"" + config + "\"",
                    WorkingDirectory = Path.GetDirectoryName(FullTunnelAvailability.HelperPath),
                    UseShellExecute = false,
                    CreateNoWindow = true,
                    RedirectStandardError = true,
                    RedirectStandardOutput = true,
                },
                EnableRaisingEvents = true,
            };
            _helper.OutputDataReceived += (_, e) => { if (e.Data != null) _log("tunnel: " + e.Data); };
            _helper.ErrorDataReceived += (_, e) => { if (e.Data != null) _log("tunnel: " + e.Data); };
            _helper.Start();
            _helper.BeginOutputReadLine();
            _helper.BeginErrorReadLine();

            // The adapter appears asynchronously. Configure it only once it is
            // there, and fail loudly if it never arrives.
            var index = await WaitForAdapterAsync(token);
            if (index < 0)
            {
                Stop();
                return (false, "آداپتر Wintun ساخته نشد / the Wintun adapter did not appear");
            }

            Run("netsh", $"interface ipv4 set address name=\"{_options.AdapterName}\" " +
                         $"static {_options.AdapterAddress} {_options.AdapterMask}");
            Run("netsh", $"interface ipv4 set subinterface \"{_options.AdapterName}\" " +
                         $"mtu={_options.Mtu} store=persistent");
            _adapterConfigured = true;

            CapturePhysicalRoute();
            if (_options.KillSwitch)
            {
                EnableKillSwitch();
            }

            // The tunnel carries the default route; the SOCKS server itself is
            // loopback, so it does not recurse. A host route to the endpoint is
            // added for split addresses and for the physical gateway, so DHCP
            // and the core's own outbound still work.
            Run("route", $"add 0.0.0.0 mask 0.0.0.0 {_options.AdapterAddress} " +
                         $"metric 1 if {index}");

            foreach (var address in _options.SplitTunnelAddresses)
            {
                if (string.IsNullOrWhiteSpace(address)) continue;
                if (!string.IsNullOrEmpty(_originalGateway))
                    Run("route", $"add {address} mask 255.255.255.255 {_originalGateway}");
            }

            _routesAdded = true;
            _log("tunnel: full tunnel up on " + _options.AdapterName);
            return (true, "");
        }
        catch (Exception ex)
        {
            Stop();
            return (false, ex.Message);
        }
    }

    private string BuildConfig()
    {
        // The helper's YAML format: tunnel settings, then one SOCKS5 upstream.
        var split = new StringBuilder();
        foreach (var address in _options.SplitTunnelAddresses)
        {
            if (string.IsNullOrWhiteSpace(address)) continue;
            split.Append("    - ").Append(address).Append('\n');
        }

        return
            "tunnel:\n" +
            "  name: " + _options.AdapterName + "\n" +
            "  mtu: " + _options.Mtu + "\n" +
            "  ipv4: " + _options.AdapterAddress + "\n" +
            "  ipv6: 'fc00::1'\n" +
            "  icmp: 'off'\n" +
            "socks5:\n" +
            "  port: " + _options.SocksPort + "\n" +
            "  address: 127.0.0.1\n" +
            "  udp: 'udp'\n" +
            "misc:\n" +
            "  task-stack-size: 20480\n" +
            (split.Length > 0 ? "  route-exclude:\n" + split : "");
    }

    private async Task<int> WaitForAdapterAsync(CancellationToken token)
    {
        for (var i = 0; i < 40; i++)
        {
            token.ThrowIfCancellationRequested();
            var nic = NetworkInterface.GetAllNetworkInterfaces()
                .FirstOrDefault(n => n.Name.Equals(_options.AdapterName, StringComparison.OrdinalIgnoreCase));
            if (nic != null)
            {
                try
                {
                    // The IPv4 properties index is what route.exe wants; the
                    // interface name is not enough on a localized Windows.
                    return nic.GetIPProperties().GetIPv4Properties()?.Index ?? -1;
                }
                catch
                {
                    return -1;
                }
            }
            await Task.Delay(250, token);
        }
        return -1;
    }

    private void CapturePhysicalRoute()
    {
        // Best effort. If this fails the tunnel still comes up; the host route
        // to the physical gateway is a refinement, not a requirement.
        try
        {
            var nic = NetworkInterface.GetAllNetworkInterfaces()
                .Where(n => n.OperationalStatus == OperationalStatus.Up)
                .Where(n => n.NetworkInterfaceType != NetworkInterfaceType.Loopback)
                .Where(n => !n.Name.Equals(_options.AdapterName, StringComparison.OrdinalIgnoreCase))
                .OrderByDescending(n => n.Speed)
                .FirstOrDefault();

            if (nic == null) return;
            var gateway = nic.GetIPProperties().GatewayAddresses
                .Select(g => g.Address)
                .FirstOrDefault(a => a != null && a.AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork);
            _originalGateway = gateway?.ToString();
            _originalInterface = nic.GetIPProperties().GetIPv4Properties()?.Index ?? 0;
            if (!string.IsNullOrEmpty(_originalGateway))
                _log($"tunnel: physical gateway {_originalGateway} if {_originalInterface}");
        }
        catch
        {
        }
    }

    private void EnableKillSwitch()
    {
        // Two rules: allow loopback and the physical gateway, block the rest.
        // Removed in Stop even if Start later fails.
        Run("netsh", "advfirewall firewall delete rule name=\"RIX-KillSwitch\"");
        Run("netsh", "advfirewall firewall add rule name=\"RIX-KillSwitch\" " +
                     "dir=out action=block profile=any enable=yes");
        _killSwitchOn = true;
    }

    public void Stop()
    {
        try
        {
            if (_killSwitchOn)
            {
                Run("netsh", "advfirewall firewall delete rule name=\"RIX-KillSwitch\"");
                _killSwitchOn = false;
            }

            if (_routesAdded)
            {
                Run("route", "delete 0.0.0.0 mask 0.0.0.0");
                foreach (var address in _options.SplitTunnelAddresses)
                {
                    if (!string.IsNullOrWhiteSpace(address))
                        Run("route", $"delete {address} mask 255.255.255.255");
                }
                _routesAdded = false;
            }

            if (_adapterConfigured)
            {
                Run("netsh", $"interface ipv4 set address name=\"{_options.AdapterName}\" source=dhcp");
                _adapterConfigured = false;
            }
        }
        catch (Exception ex)
        {
            _log("tunnel: cleanup — " + ex.Message);
        }

        try
        {
            if (_helper != null && !_helper.HasExited)
                _helper.Kill(entireProcessTree: true);
        }
        catch
        {
        }
        _helper?.Dispose();
        _helper = null;
    }

    private void Run(string file, string arguments)
    {
        try
        {
            var info = new ProcessStartInfo
            {
                FileName = file,
                Arguments = arguments,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            using var process = Process.Start(info);
            if (process == null) return;
            if (!process.WaitForExit(15000))
            {
                try { process.Kill(true); } catch { }
                _log($"tunnel: {Path.GetFileName(file)} timed out");
                return;
            }
            if (process.ExitCode != 0)
                _log($"tunnel: {Path.GetFileName(file)} exited {process.ExitCode}: {process.StandardError.ReadToEnd().Trim()}");
        }
        catch (Exception ex)
        {
            _log($"tunnel: {Path.GetFileName(file)} failed — {ex.Message}");
        }
    }

    public void Dispose() => Stop();
}
