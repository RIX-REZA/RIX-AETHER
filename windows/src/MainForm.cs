using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;
using Microsoft.Win32;
using Rix.Windows.Core;

namespace Rix.Windows;

/// <summary>
/// The window: a WebView2 showing the screens, and the C# behind it.
///
/// The chrome is drawn by the page rather than by Windows -- the window is
/// borderless and answers hit tests itself -- so the app looks like one thing
/// instead of a form with a browser in it, and both themes are the page's own.
/// </summary>
public sealed class MainForm : Form
{
    private const string VirtualHost = "rix.local";

    private readonly WebView2 _web = new();
    private readonly RixSession _session = new();
    private readonly NotifyIcon _tray = new();
    private readonly AppState _state;
    private readonly IpScannerService _scanner = new();
    private readonly CoreSupervisor _core;

    private ToolStripMenuItem _trayToggle;
    private ToolStripMenuItem _trayProxyOff;
    private bool _webReady;
    private bool _allowClose;
    private Snapshot _last = new();
    private Process _tunnel;
    private string _tunnelStopFile;
    private volatile bool _fullTunnelOn;
    private readonly PeerPool _peerPool = new();
    private readonly NetworkWatch _networkWatch;
    private readonly IIpProbe _probe = new TlsIpProbe();
    private string _lastPeer;
    private RixState _lastCoreState = RixState.Disconnected;

    public MainForm(AppState state)
    {
        _state = state;

        _peerPool.Replace(_scanner.Cache?.Ranked ?? new List<ScanCandidate>());

        _core = new CoreSupervisor(
            CreatePathAsync,
            // Resilience: health checks, fast first retries, exponential
            // backoff after them, and no giving up on its own.
            Resilience.Options(),
            new BackoffPolicy(TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(45)))
        {
            // Rotation, not a pin: every attempt asks the pool for its current
            // best eligible peer, and a failed one cools down.
            BestAddress = () => _peerPool.Pick(),
            PreVerify = VerifyPeerAsync,
            CandidateRejected = c => _peerPool.ReportFailure(c.Endpoint),
        };
        _core.PathReady = async _ =>
        {
            if (_state.Options.TunnelMode == "tunnel") await StartFullTunnelAsync();
        };
        _core.Changed += OnCoreChanged;
        _core.Log += line => Send(new JsonObject { ["type"] = "log", ["line"] = "core: " + line });

        // The machine moving is the most common way a healthy-looking socket
        // goes dead; the supervisor is kicked on the first debounce instead of
        // waiting for a health check to notice.
        _networkWatch = new NetworkWatch(reason =>
        {
            Send(new JsonObject { ["type"] = "log", ["line"] = reason });
            _core.NetworkChanged(reason);
        });

        _scanner.Progress += OnScanProgress;
        _scanner.Completed += OnScanCompleted;
        _scanner.BestChanged += OnBestChanged;
        _scanner.Log += line => Send(new JsonObject { ["type"] = "log", ["line"] = line });

        Text = "RIX";
        FormBorderStyle = FormBorderStyle.None;
        StartPosition = FormStartPosition.CenterScreen;
        MinimumSize = new Size(880, 620);
        ClientSize = new Size(1040, 700);
        BackColor = Color.FromArgb(11, 12, 18);
        DoubleBuffered = true;
        try { Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath); } catch { }

        _web.Dock = DockStyle.Fill;
        _web.DefaultBackgroundColor = Color.FromArgb(11, 12, 18);
        Controls.Add(_web);

        BuildTray();

        _session.Changed += OnSessionChanged;
        _session.Log += line => Send(new JsonObject { ["type"] = "log", ["line"] = line });

        Load += async (_, _) => await InitialiseWebAsync();
        FormClosing += OnFormClosing;
    }

    // ------------------------------------------------------------------ webview

    private async Task InitialiseWebAsync()
    {
        try
        {
            // A named folder, not a file:// URL: the page then behaves like a
            // normal origin, and nothing else on the machine can reach it.
            var dataDir = Path.Combine(AppState.Dir, "webview");
            Directory.CreateDirectory(dataDir);

            var environment = await CoreWebView2Environment.CreateAsync(null, dataDir);
            await _web.EnsureCoreWebView2Async(environment);

            var core = _web.CoreWebView2;
            var settings = core.Settings;
            settings.AreDefaultContextMenusEnabled = false;
            settings.IsZoomControlEnabled = false;
            settings.IsStatusBarEnabled = false;
            settings.AreBrowserAcceleratorKeysEnabled = false;
            settings.AreDevToolsEnabled = false;
            settings.IsPasswordAutosaveEnabled = false;
            settings.IsGeneralAutofillEnabled = false;

            // The screens come out of the assembly, not off the disk.
            core.AddWebResourceRequestedFilter(
                "https://" + VirtualHost + "/*", CoreWebView2WebResourceContext.All);
            core.WebResourceRequested += OnWebResourceRequested;

            core.WebMessageReceived += OnWebMessage;
            core.NavigationCompleted += (_, _) =>
            {
                _webReady = true;
                SendInit();
            };

            if (UiAssets.Open("index.html") == null)
            {
                // Only reachable from a build that lost its embedded resources,
                // and worth saying out loud rather than showing a white window.
                throw new InvalidOperationException("the interface is missing from this build");
            }

            core.Navigate("https://" + VirtualHost + "/index.html");
        }
        catch (Exception ex)
        {
            // The one real dependency of this app, and the only failure worth
            // stopping for: without it there is no interface at all.
            MessageBox.Show(
                "RIX needs the Microsoft Edge WebView2 runtime, which is not installed.\n\n" +
                "نصب WebView2 لازم است.\n\n" + ex.Message,
                "RIX", MessageBoxButtons.OK, MessageBoxIcon.Error);
            _allowClose = true;
            Close();
        }
    }

    /// <summary>
    /// Answers the page's own requests for its stylesheet and script. Only
    /// rix.local is filtered, so nothing else the WebView asks for -- and it
    /// asks for nothing else -- is touched.
    /// </summary>
    private void OnWebResourceRequested(object sender, CoreWebView2WebResourceRequestedEventArgs e)
    {
        try
        {
            var uri = new Uri(e.Request.Uri);
            var asset = UiAssets.Open(uri.AbsolutePath);

            if (asset == null)
            {
                e.Response = _web.CoreWebView2.Environment.CreateWebResourceResponse(
                    null, 404, "Not Found", "Content-Type: text/plain");
                return;
            }

            e.Response = _web.CoreWebView2.Environment.CreateWebResourceResponse(
                asset.Value.Content, 200, "OK", "Content-Type: " + asset.Value.Type);
        }
        catch
        {
            // A failed sub-resource leaves the page on screen with a missing
            // piece rather than a dialog over it.
        }
    }

    private void OnWebMessage(object sender, CoreWebView2WebMessageReceivedEventArgs e)
    {
        JsonObject message;
        try
        {
            message = JsonNode.Parse(e.WebMessageAsJson) as JsonObject;
        }
        catch
        {
            return;
        }
        if (message == null) return;

        var type = message["type"]?.GetValue<string>() ?? "";
        switch (type)
        {
            case "ready":
                SendInit();
                break;

            case "save":
                if (message["options"] is JsonObject options)
                {
                    try
                    {
                        var parsed = options.Deserialize<RixOptions>(JsonOptions);
                        if (parsed != null)
                        {
                            _state.Options = parsed;
                            _state.Save();
                            _scanner.Options = parsed.ToScanOptions();
                            _scanner.IntervalMinutes = Math.Clamp(parsed.ScanIntervalMinutes, 5, 1440);
                            if (!parsed.ScanEnabled) _scanner.Stop();
                            else if (parsed.ScanOnLaunch) _scanner.Start();
                            Send(new JsonObject { ["type"] = "saved" });
                        }
                    }
                    catch (Exception ex)
                    {
                        Send(new JsonObject { ["type"] = "toast", ["text"] = "تنظیمات ذخیره نشد: " + ex.Message });
                    }
                }
                break;

            case "connect":
                _ = ConnectAsync();
                break;

            case "disconnect":
                _ = DisconnectAsync();
                break;

            case "proxyOff":
                StopFullTunnel();
                _session.DisableSystemProxy();
                Send(new JsonObject { ["type"] = "toast", ["text"] = "پروکسی سیستم خاموش شد / system proxy off" });
                SendState(_session.Current);
                break;

            case "mode":
                var mode = message["mode"]?.GetValue<string>() ?? "proxy";
                if (mode != "proxy" && mode != "tunnel") break;
                if (_state.Options.TunnelMode != mode)
                {
                    var wasRunning = _session.Current.Phase == Phase.Running;
                    _state.Options.TunnelMode = mode;
                    _state.Save();
                    if (wasRunning)
                    {
                        // Applying the mode means rebuilding the carriage, so
                        // the connection is restarted deliberately and said so.
                        Send(new JsonObject { ["type"] = "toast", ["text"] = "حالت عوض شد؛ اتصال دوباره برقرار می‌شود / mode changed, reconnecting" });
                        _ = DisconnectAsync().ContinueWith(_ => _core.Start());
                    }
                }
                SendState(_session.Current);
                break;

            case "scanNow":
                _ = ScanNowAsync();
                break;

            case "stopTunnel":
                StopFullTunnel();
                SendState(_session.Current);
                break;

            case "state":
                SendState(_session.Current);
                break;

            case "probe":
                _ = ProbeAsync();
                break;

            case "autostart":
                var on = message["on"]?.GetValue<bool>() ?? false;
                SetAutoStart(on);
                _state.Options.StartWithWindows = on;
                _state.Save();
                Send(new JsonObject { ["type"] = "autostart", ["on"] = AutoStartEnabled() });
                break;

            case "checkUpdate":
                _ = CheckUpdateAsync();
                break;

            case "openUrl":
                var url = message["url"]?.GetValue<string>() ?? "";
                // Only project links: the two fixed URLs and the release page
                // the update check itself handed back. A WebView message must
                // not become a way to launch an arbitrary URL.
                if (url == "https://t.me/rixpanel" ||
                    url == "https://github.com/RIX-REZA/RIX-AETHER" ||
                    url.StartsWith(UpdateCheck.ReleasesBase + "/", StringComparison.Ordinal))
                {
                    try
                    {
                        Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
                    }
                    catch
                    {
                    }
                }
                break;

            case "openFolder":
                OpenFolder();
                break;

            case "copy":
                SetClipboard(message["text"]?.GetValue<string>() ?? "");
                break;

            case "minimize":
                WindowState = FormWindowState.Minimized;
                break;

            case "maximize":
                WindowState = WindowState == FormWindowState.Maximized
                    ? FormWindowState.Normal
                    : FormWindowState.Maximized;
                break;

            case "close":
                Close();
                break;

            case "quit":
                _allowClose = true;
                Close();
                break;

            case "drag":
                DragWindow();
                break;
        }
    }

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
    };

    /// <summary>For `--connect`, when Windows started the app for the user.</summary>
    public Task AutoConnectAsync() => ConnectAsync();

    private Task<IConnectionPath> CreatePathAsync(CancellationToken token)
    {
        // Full Tunnel still uses the same Aether session; only the machine's
        // carriage differs (Wintun instead of WinINET), so the path is built
        // with or without the system proxy.
        var full = _state.Options.TunnelMode == "tunnel";
        return Task.FromResult<IConnectionPath>(
            new SessionConnectionPath(_session, PeerOptions, useSystemProxy: !full));
    }

    /// <summary>
    /// Direct pre-verification of the peer the pool proposed. Runs straight
    /// out -- never through the tunnel -- so a failed check cannot loop.
    /// </summary>
    private async Task<bool> VerifyPeerAsync(ScanCandidate candidate)
    {
        try
        {
            var options = _state.Options.ToScanOptions();
            options.MeasureSpeed = false;
            options.Samples = 1;
            var result = await _probe.ProbeAsync(candidate.Address, candidate.Port, options, CancellationToken.None);
            return result.Ok;
        }
        catch
        {
            return false;
        }
    }

    private void OnScanCompleted(IpScanCache cache)
    {
        _peerPool.Replace(cache.Ranked);
        SendScan(cache.Ranked.FirstOrDefault(), null, false, cache);
    }

    /// <summary>
    /// The options handed to the core, with the scanner's winner substituted
    /// for the peer when the user has not pinned one.
    ///
    /// The scan only steers the root protocol. Psiphon chooses its own path
    /// behind its own gates and ignores --peer, so when the user selected it,
    /// the scanner's pick is left out rather than silently dropped.
    /// </summary>
    private RixOptions PeerOptions()
    {
        var options = _state.Options;
        if (options.Psiphon) return options;
        if (!options.ScanEnabled || !options.ScanUseBestPeer) return options;
        if (!string.IsNullOrWhiteSpace(options.Peer)) return options;

        var best = _peerPool.Pick() ?? _scanner.Best;
        if (best == null) return options;
        _lastPeer = best.Endpoint;
        return options with { Peer = best.Endpoint };
    }

    private async Task ConnectAsync()
    {
        _core.Start();
        _state.Save();
        SendState(_session.Current);
        UpdateTray();
        await Task.CompletedTask;
    }

    // ------------------------------------------------------------- full tunnel

    /// <summary>
    /// Starts RIX-Tunnel.exe elevated. The helper owns the adapter, the routes
    /// and the optional firewall rule; the UI process stays unelevated.
    /// </summary>
    private async Task<bool> StartFullTunnelAsync()
    {
        if (_fullTunnelOn) return true;

        var available = FullTunnelAvailability.Check();
        if (!available.Available)
        {
            Send(new JsonObject { ["type"] = "toast", ["text"] = available.Reason });
            Send(new JsonObject { ["type"] = "log", ["line"] = "tunnel: " + available.Reason });
            return false;
        }

        StopFullTunnel();

        try
        {
            var exe = Path.Combine(AppContext.BaseDirectory, "core", "RIX-Tunnel.exe");
            if (!File.Exists(exe)) exe = Path.Combine(AppContext.BaseDirectory, "RIX-Tunnel.exe");
            if (!File.Exists(exe))
            {
                Send(new JsonObject { ["type"] = "toast", ["text"] = "RIX-Tunnel.exe پیدا نشد / tunnel helper is missing" });
                return false;
            }

            var stopFile = Path.Combine(AppState.Dir, "tunnel.stop");
            try { File.Delete(stopFile); } catch { }
            var stateFile = Path.Combine(AppState.Dir, "tunnel.state");
            try { File.Delete(stateFile); } catch { }

            var socks = _session.Current.Port > 0 ? _session.Current.Port : _state.Options.SocksPort;
            var split = _state.Options.SplitTunnel ?? "";
            var arguments =
                "--data \"" + AppState.Dir + "\" " +
                "--socks " + socks + " " +
                "--adapter RIX " +
                "--stop-file \"" + stopFile + "\"" +
                (_state.Options.KillSwitch ? " --kill" : "") +
                (string.IsNullOrWhiteSpace(split) ? "" : " --split \"" + split + "\"");

            _tunnelStopFile = stopFile;
            _tunnel = Process.Start(new ProcessStartInfo
            {
                FileName = exe,
                Arguments = arguments,
                UseShellExecute = true,
                Verb = "runas",
                WorkingDirectory = AppContext.BaseDirectory,
            });
            if (_tunnel == null) return false;

            // Wait for the helper to say READY, but never forever: a helper
            // stuck on a missing DLL would otherwise hold the connect button.
            for (var i = 0; i < 60; i++)
            {
                await Task.Delay(250);
                if (_tunnel.HasExited) break;
                try
                {
                    if (File.Exists(stateFile) && File.ReadAllText(stateFile).StartsWith("ready"))
                    {
                        _fullTunnelOn = true;
                        Send(new JsonObject { ["type"] = "log", ["line"] = "tunnel: full tunnel ready" });
                        SendState(_session.Current);
                        return true;
                    }
                }
                catch
                {
                }
            }

            var reason = _tunnel.HasExited
                ? "RIX-Tunnel exited with code " + _tunnel.ExitCode
                : "RIX-Tunnel did not become ready";
            Send(new JsonObject { ["type"] = "log", ["line"] = "tunnel: " + reason });
            StopFullTunnel();
            return false;
        }
        catch (System.ComponentModel.Win32Exception ex) when (ex.NativeErrorCode == 1223)
        {
            // UAC was declined. Nothing was changed on the machine.
            Send(new JsonObject { ["type"] = "toast", ["text"] = "درخواست دسترسی مدیر رد شد / UAC was declined" });
            return false;
        }
        catch (Exception ex)
        {
            Send(new JsonObject { ["type"] = "log", ["line"] = "tunnel: " + ex.Message });
            return false;
        }
    }

    private void StopFullTunnel()
    {
        if (_tunnel == null && !_fullTunnelOn) return;

        try
        {
            if (!string.IsNullOrEmpty(_tunnelStopFile))
                File.WriteAllText(_tunnelStopFile, "stop");
        }
        catch
        {
        }

        try
        {
            if (_tunnel != null && !_tunnel.HasExited)
                _tunnel.WaitForExit(8000);
            if (_tunnel != null && !_tunnel.HasExited)
                _tunnel.Kill(entireProcessTree: true);
        }
        catch
        {
        }

        try { _tunnel?.Dispose(); } catch { }
        _tunnel = null;
        _tunnelStopFile = null;
        _fullTunnelOn = false;
    }

    private async Task DisconnectAsync()
    {
        StopFullTunnel();
        await _core.StopAsync();
        await _session.DisconnectAsync();
        SendState(_session.Current);
        UpdateTray();
    }

    private async Task ScanNowAsync()
    {
        var options = _state.Options;
        _scanner.Options = options.ToScanOptions();
        _scanner.IntervalMinutes = Math.Clamp(options.ScanIntervalMinutes, 5, 1440);
        SendScan(null, new ScanProgress { Running = true, Total = 1 }, true, null);
        var cache = await _scanner.ScanNowAsync(CancellationToken.None);
        SendScan(cache.Ranked.FirstOrDefault(), new ScanProgress
        {
            Running = false,
            Done = cache.Ranked.Count,
            Total = cache.Ranked.Count,
        }, false, cache);
        SendState(_session.Current);
    }

    /// <summary>
    /// Answers the home screen's update button. The page never opens a URL it
    /// was not handed back with an "available" flag, and this is where the
    /// flag and the URL come from.
    /// </summary>
    private async Task CheckUpdateAsync()
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(15) };
        var info = await UpdateCheck.CheckAsync(http, CancellationToken.None);
        Send(new JsonObject
        {
            ["type"] = "update",
            ["available"] = info.Available,
            ["version"] = info.Version,
            ["url"] = info.Url,
            ["error"] = info.Error,
        });
        if (!string.IsNullOrEmpty(info.Error))
            Send(new JsonObject { ["type"] = "log", ["line"] = "update: " + info.Error });
    }

    private async Task ProbeAsync()
    {
        var exit = await _session.RefreshEgressAsync();
        var local = await _session.LocalAsync();
        SendState(_session.Current);
        if (exit == null && local == null)
        {
            Send(new JsonObject { ["type"] = "toast", ["text"] = "پاسخی نیامد / no answer" });
        }
    }

    // ------------------------------------------------------------------ pushing

    private void OnSessionChanged(Snapshot snapshot)
    {
        _last = snapshot;
        SendState(snapshot);
        UpdateTray();
    }

    private void OnCoreChanged(CoreStatus status)
    {
        // The pool learns what actually carried traffic: a peer that reached
        // Connected clears its debt, a peer that dropped back out of it takes
        // on a cooldown, so the next reconnect rotates away from it instead of
        // knocking on the same dead door.
        if (_lastCoreState is RixState.Connecting or RixState.Reconnecting &&
            status.State is RixState.Connected or RixState.Degraded)
        {
            _peerPool.ReportSuccess(_lastPeer);
        }
        else if (_lastCoreState is RixState.Connected or RixState.Degraded or RixState.Connecting &&
                 status.State is RixState.Reconnecting or RixState.Disconnected)
        {
            _peerPool.ReportFailure(_lastPeer);
        }
        _lastCoreState = status.State;

        _coreStatus = status;
        SendState(_session.Current);
        UpdateTray();
    }

    private CoreStatus _coreStatus = new();
    private readonly FullTunnelAvailability _tunnelAvailability = FullTunnelAvailability.Check();

    private void OnScanProgress(ScanProgress progress) => SendScan(progress.Best, progress, true, null);

    private void OnBestChanged(ScanCandidate best)
    {
        Send(new JsonObject { ["type"] = "log", ["line"] = "scan: best edge " + best.Endpoint });
        // A better address is used at the next reconnect. Swapping the peer of
        // a live core is not something the core supports without restarting it,
        // so the supervisor's failover (which picks BestAddress) is the path.
    }

    private void SendScan(ScanCandidate best, ScanProgress progress, bool running, IpScanCache cache)
    {
        var ranked = (cache?.Ranked ?? _scanner.Cache?.Ranked ?? new List<ScanCandidate>())
            .Take(8)
            .Select(c => new JsonObject
            {
                ["address"] = c.Address,
                ["port"] = c.Port,
                ["latency"] = Math.Round(c.LatencyMs, 1),
                ["jitter"] = Math.Round(c.JitterMs, 1),
                ["success"] = Math.Round(c.SuccessRate, 3),
                ["score"] = Math.Round(c.Score, 4),
                ["speed"] = Math.Round(c.SpeedBps),
            })
            .ToList();

        var message = new JsonObject
        {
            ["type"] = "scan",
            ["running"] = running,
            ["done"] = progress?.Done ?? 0,
            ["total"] = progress?.Total ?? 0,
            ["fraction"] = progress?.Fraction ?? (running ? 0 : 1),
            ["current"] = progress?.Current ?? "",
            ["best"] = best == null ? null : new JsonObject
            {
                ["address"] = best.Address,
                ["port"] = best.Port,
                ["endpoint"] = best.Endpoint,
                ["latency"] = Math.Round(best.LatencyMs, 1),
                ["jitter"] = Math.Round(best.JitterMs, 1),
                ["success"] = Math.Round(best.SuccessRate, 3),
                ["score"] = Math.Round(best.Score, 4),
            },
            ["ranked"] = new JsonArray(ranked.ToArray()),
            ["scannedAt"] = (cache?.ScannedAt ?? _scanner.Cache?.ScannedAt)?.ToString("u") ?? "",
            ["source"] = cache?.Source ?? _scanner.Cache?.Source ?? "",
        };
        Send(message);
    }

    private void SendState(Snapshot snapshot)
    {
        var message = new JsonObject
        {
            ["type"] = "state",
            ["phase"] = snapshot.Phase.ToString().ToLowerInvariant(),
            ["message"] = snapshot.Message,
            ["transport"] = snapshot.Transport,
            ["transportLabel"] = snapshot.TransportLabel,
            ["port"] = snapshot.Port,
            ["proxyOn"] = snapshot.ProxyOn,
            ["up"] = snapshot.Up,
            ["down"] = snapshot.Down,
            ["uptime"] = (long)snapshot.Uptime.TotalSeconds,
            ["psiphon"] = snapshot.Psiphon,
            ["psiphonRegion"] = snapshot.PsiphonRegion,
            ["egress"] = EgressJson(snapshot.Egress),
            ["local"] = EgressJson(snapshot.Local),
            ["rixState"] = _coreStatus.State.ToString().ToLowerInvariant(),
            ["rixDetail"] = _coreStatus.Detail,
            ["rixReconnects"] = _coreStatus.ReconnectAttempt,
            ["tunnelMode"] = _state.Options.TunnelMode,
            ["fullTunnelOn"] = _fullTunnelOn,
            ["tunnelAvailable"] = _tunnelAvailability.Available,
            ["tunnelWhy"] = _tunnelAvailability.Reason,
            ["scannedBest"] = ScanBestJson(),
        };
        Send(message);
    }

    private JsonNode ScanBestJson()
    {
        var best = _scanner.Best;
        return best == null ? null : new JsonObject
        {
            ["address"] = best.Address,
            ["port"] = best.Port,
            ["endpoint"] = best.Endpoint,
            ["latency"] = Math.Round(best.LatencyMs, 1),
            ["jitter"] = Math.Round(best.JitterMs, 1),
        };
    }

    private static JsonNode EgressJson(Egress egress) => egress == null
        ? null
        : new JsonObject
        {
            ["ip"] = egress.Ip,
            ["country"] = egress.Country,
            ["colo"] = egress.Colo,
            ["warp"] = egress.Warp,
        };

    private void SendInit()
    {
        var options = JsonSerializer.SerializeToNode(_state.Options, JsonOptions);
        Send(new JsonObject
        {
            ["type"] = "init",
            ["options"] = options,
            // The assembly's own version, which is set in the csproj and is what
            // the installer writes into Apps & Features; the fallback only
            // covers a build that somehow has no assembly version at all.
            ["version"] = typeof(MainForm).Assembly.GetName().Version?.ToString(3) ?? "1.0.7",
            ["core"] = AetherProcess.Locate() ?? "",
            ["psiphon"] = File.Exists(Path.Combine(AppContext.BaseDirectory, "core", "pt", "psiphon-tunnel-core.exe")),
            ["autostart"] = AutoStartEnabled(),
            ["proxy"] = SystemProxy.Current().Server,
            ["tunnelAvailable"] = _tunnelAvailability.Available,
            ["tunnelWhy"] = _tunnelAvailability.Reason,
            ["elevated"] = _tunnelAvailability.Elevated,
            ["scan"] = ScanSnapshotJson(),
        });
        SendState(_last);

        if (_state.Options.ScanEnabled && _state.Options.ScanOnLaunch)
        {
            _scanner.Options = _state.Options.ToScanOptions();
            _scanner.IntervalMinutes = Math.Clamp(_state.Options.ScanIntervalMinutes, 5, 1440);
            _scanner.Start();
        }
    }

    private JsonObject ScanSnapshotJson()
    {
        var cache = _scanner.Cache;
        return new JsonObject
        {
            ["running"] = _scanner.IsScanning,
            ["fraction"] = 0,
            ["scannedAt"] = cache?.ScannedAt.ToString("u") ?? "",
            ["source"] = cache?.Source ?? "",
            ["best"] = ScanBestJson(),
        };
    }

    /// <summary>
    /// Hands a message to the page. WebView2 refuses calls from any thread but
    /// the one it was created on, and the session raises its events from
    /// wherever it happens to be, so this always marshals.
    /// </summary>
    private void Send(JsonObject message)
    {
        if (!_webReady || IsDisposed || !IsHandleCreated) return;

        var json = message.ToJsonString();
        try
        {
            if (InvokeRequired) BeginInvoke(() => Post(json));
            else Post(json);
        }
        catch (InvalidOperationException)
        {
            // The window went away mid-post; nothing to send to. This also
            // covers ObjectDisposedException, which is a kind of it — a second
            // clause for that would be unreachable.
        }
    }

    private void Post(string json)
    {
        try { _web.CoreWebView2?.PostWebMessageAsJson(json); }
        catch { }
    }

    // --------------------------------------------------------------------- tray

    private void BuildTray()
    {
        _trayToggle = new ToolStripMenuItem("اتصال / Connect", null, (_, _) =>
        {
            if (_session.Current.Phase == Phase.Running) _ = DisconnectAsync();
            else _ = ConnectAsync();
        });

        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem("نمایش پنجره / Show", null, (_, _) => Restore()));
        menu.Items.Add(_trayToggle);
        _trayProxyOff = new ToolStripMenuItem("خاموش کردن پروکسی سیستم / System proxy off", null, (_, _) =>
        {
            StopFullTunnel();
            _session.DisableSystemProxy();
            SendState(_session.Current);
        });
        menu.Items.Add(_trayProxyOff);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add(new ToolStripMenuItem("خروج / Quit", null, (_, _) =>
        {
            _allowClose = true;
            Close();
        }));

        _tray.Icon = Icon;
        _tray.Text = "RIX";
        _tray.ContextMenuStrip = menu;
        _tray.DoubleClick += (_, _) => Restore();
        _tray.Visible = true;
    }

    private void UpdateTray()
    {
        // The session and the supervisor raise their events from background
        // threads, and NotifyIcon is a window object. Marshalling here keeps
        // both callers safe instead of making each remember to.
        if (IsDisposed || !IsHandleCreated) return;
        if (InvokeRequired)
        {
            try { BeginInvoke(UpdateTray); } catch { }
            return;
        }

        var running = _last.Phase == Phase.Running;
        var detail = running
            ? "متصل — " + (_last.Egress?.Ip ?? _last.TransportLabel)
            : _last.Phase.ToString();
        // NotifyIcon caps this at 63 characters and throws past it.
        _tray.Text = ("RIX — " + detail).Length > 62
            ? ("RIX — " + detail)[..62]
            : "RIX — " + detail;
        _trayToggle.Text = running ? "قطع / Disconnect" : "اتصال / Connect";
        if (_trayProxyOff != null)
            _trayProxyOff.Enabled = SystemProxy.Owned || _fullTunnelOn;
    }

    private void Restore()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    // -------------------------------------------------------------------- misc

    private void OnFormClosing(object sender, FormClosingEventArgs e)
    {
        // The close button hides to the tray, the way a background tunnel
        // should: the machine is still being carried and the user did not
        // necessarily mean to stop that.
        if (!_allowClose && _state.Options.MinimizeToTray && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            return;
        }

        _tray.Visible = false;

        // Synchronous on purpose, and deliberately not the async teardown: this
        // runs on the UI thread, and awaiting there while the window is closing
        // is how an app hangs on exit. The helper is told to stop first (it
        // removes routes and firewall rules), then the session's own Dispose
        // cancels the connect, turns the proxy off and stops the core.
        StopFullTunnel();
        try { _core.Dispose(); } catch { }
        try { _scanner.Dispose(); } catch { }
        try { _networkWatch.Dispose(); } catch { }
        _session.Dispose();
        _state.Save();
    }

    private static void SetClipboard(string text)
    {
        try
        {
            if (!string.IsNullOrEmpty(text)) Clipboard.SetText(text);
        }
        catch
        {
        }
    }

    private void OpenFolder()
    {
        try
        {
            Directory.CreateDirectory(AppState.Dir);
            System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo
            {
                FileName = AppState.Dir,
                UseShellExecute = true,
            });
        }
        catch
        {
        }
    }

    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    private static bool AutoStartEnabled()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(RunKey);
            return key?.GetValue("RIX") != null;
        }
        catch
        {
            return false;
        }
    }

    private static void SetAutoStart(bool on)
    {
        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
            if (key == null) return;
            // --tray so it does not steal focus at logon, --connect so starting
            // with Windows means the tunnel is up rather than merely loaded.
            if (on) key.SetValue("RIX", "\"" + Application.ExecutablePath + "\" --tray --connect");
            else key.DeleteValue("RIX", throwOnMissingValue: false);
        }
        catch
        {
        }
    }

    // ------------------------------------------------------------- window chrome

    private const int WmNcHitTest = 0x0084;
    private const int WmNcLButtonDown = 0x00A1;
    private const int HtCaption = 0x0002;

    private const int HtLeft = 10, HtRight = 11, HtTop = 12, HtTopLeft = 13,
                      HtTopRight = 14, HtBottom = 15, HtBottomLeft = 16, HtBottomRight = 17;

    /// <summary>
    /// The resize edges the borderless window would otherwise not have. The
    /// margin follows the DPI so the grip stays the same size to the hand.
    /// </summary>
    protected override void WndProc(ref Message m)
    {
        if (m.Msg == WmNcHitTest && WindowState == FormWindowState.Normal)
        {
            base.WndProc(ref m);

            var screen = new Point(m.LParam.ToInt32() & 0xffff, (m.LParam.ToInt32() >> 16) & 0xffff);
            var local = PointToClient(screen);
            var margin = (int)Math.Ceiling(6 * DeviceDpi / 96.0);

            var left = local.X <= margin;
            var right = local.X >= ClientSize.Width - margin;
            var top = local.Y <= margin;
            var bottom = local.Y >= ClientSize.Height - margin;

            m.Result = (left, right, top, bottom) switch
            {
                (true, _, true, _) => HtTopLeft,
                (_, true, true, _) => HtTopRight,
                (true, _, _, true) => HtBottomLeft,
                (_, true, _, true) => HtBottomRight,
                (true, _, _, _) => HtLeft,
                (_, true, _, _) => HtRight,
                (_, _, true, _) => HtTop,
                (_, _, _, true) => HtBottom,
                _ => m.Result.ToInt32(),
            };
            return;
        }

        base.WndProc(ref m);
    }

    private void DragWindow()
    {
        ReleaseCapture();
        SendMessage(Handle, WmNcLButtonDown, HtCaption, 0);
    }

    protected override void OnHandleCreated(EventArgs e)
    {
        base.OnHandleCreated(e);

        // Rounded corners, on the builds that have them. A failure here is a
        // square window, not a problem.
        try
        {
            var round = 2;   // DWMWCP_ROUND
            DwmSetWindowAttribute(Handle, 33 /* DWMWA_WINDOW_CORNER_PREFERENCE */,
                ref round, sizeof(int));
        }
        catch
        {
        }
    }

    [DllImport("user32.dll")]
    private static extern bool ReleaseCapture();

    [DllImport("user32.dll")]
    private static extern IntPtr SendMessage(IntPtr hWnd, int msg, int wParam, int lParam);

    [DllImport("dwmapi.dll")]
    private static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            _tray.Visible = false;
            _tray.Dispose();
            // Idempotent: the closing path has usually done this already, and a
            // second call must not touch the machine's proxy again.
            _session.Dispose();
        }
        base.Dispose(disposing);
    }
}