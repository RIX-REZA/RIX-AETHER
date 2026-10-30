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

    private ToolStripMenuItem _trayToggle;
    private bool _webReady;
    private bool _allowClose;
    private Snapshot _last = new();

    public MainForm(AppState state)
    {
        _state = state;

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
                _ = _session.DisconnectAsync();
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

    private async Task ConnectAsync()
    {
        await _session.ConnectAsync(_state.Options, useSystemProxy: true);
        _state.Save();
        SendState(_session.Current);
        UpdateTray();
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
            ["egress"] = Egress(snapshot.Egress),
            ["local"] = Egress(snapshot.Local),
        };
        Send(message);
    }

    private static JsonNode Egress(Egress egress) => egress == null
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
            ["version"] = typeof(MainForm).Assembly.GetName().Version?.ToString(3) ?? "1.0.5",
            ["core"] = AetherProcess.Locate() ?? "",
            ["psiphon"] = File.Exists(Path.Combine(AppContext.BaseDirectory, "core", "pt", "psiphon-tunnel-core.exe")),
            ["autostart"] = AutoStartEnabled(),
            ["proxy"] = SystemProxy.Current().Server,
        });
        SendState(_last);
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
            // The window went away mid-post; nothing to send to.
        }
        catch (ObjectDisposedException)
        {
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
            if (_session.Current.Phase == Phase.Running) _ = _session.DisconnectAsync();
            else _ = ConnectAsync();
        });

        var menu = new ContextMenuStrip();
        menu.Items.Add(new ToolStripMenuItem("نمایش پنجره / Show", null, (_, _) => Restore()));
        menu.Items.Add(_trayToggle);
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
        var running = _last.Phase == Phase.Running;
        var detail = running
            ? "متصل — " + (_last.Egress?.Ip ?? _last.TransportLabel)
            : _last.Phase.ToString();
        // NotifyIcon caps this at 63 characters and throws past it.
        _tray.Text = ("RIX — " + detail).Length > 62
            ? ("RIX — " + detail)[..62]
            : "RIX — " + detail;
        _trayToggle.Text = running ? "قطع / Disconnect" : "اتصال / Connect";
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
        // is how an app hangs on exit. The session's own Dispose cancels the
        // connect, turns the proxy off and stops the core.
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