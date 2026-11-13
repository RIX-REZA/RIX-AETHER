using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace Rix.Windows.Core;

/// <summary>
/// The Windows system proxy, which is how everything on the machine is carried
/// without a TUN device or a driver.
///
/// This is the honest description of the trade: a WinINET proxy setting covers
/// Edge, Chrome, Office and everything else that asks Windows where the proxy
/// is, but it does not cover Firefox (which keeps its own) or applications that
/// open their own sockets. Full Tunnel is the mode that covers those; see
/// <see cref="FullTunnel"/>.
///
/// The registry lives under HKCU, so this needs no elevation, and it is
/// reverted on exit. The revert is also armed against a crash: the previous
/// values go into a lock file *before* the registry is touched, and the next
/// launch puts them back if the lock is still there.
///
/// The backup/restore rules live in <see cref="ProxyRestore"/> so they can be
/// tested without a Windows registry. This class is only the WinINET store and
/// the public entry points the rest of the app already used.
/// </summary>
public static class SystemProxy
{
    private static readonly ProxyRestore Engine =
        new(new WinInetProxyStore(), Path.Combine(AppState.Dir, "proxy.lock"));

    /// <summary>127.0.0.1:1819, or 1822 when Psiphon is the exit.</summary>
    public static string Server(int port) => "127.0.0.1:" + port;

    /// <summary>Sent straight out instead of into the tunnel.</summary>
    public const string Bypass = ProxyRestore.Bypass;

    /// <summary>Whether a proxy is set right now, and where it points.</summary>
    public static (bool Enabled, string Server) Current()
    {
        var (enabled, server, _) = new WinInetProxyStore().Read();
        return (enabled, server);
    }

    /// <summary>
    /// Points the machine at the local proxy. Returns false when the registry
    /// refused, in which case nothing was half-written: the caller is told so it
    /// can say the proxy is not on rather than pretending.
    /// </summary>
    public static bool Enable(int port, out string error) => Engine.Enable(port, out error);

    /// <summary>
    /// Puts back whatever <see cref="Enable"/> found. Does nothing at all
    /// without a lock file: a machine whose owner runs their own proxy must not
    /// have it switched off by an app that had nothing to do with it.
    /// </summary>
    public static void Disable() => Engine.Disable();

    /// <summary>
    /// Called once at startup. If a lock file survived, the last run was killed
    /// before it could revert, and the machine is still pointing at a proxy that
    /// is not running.
    /// </summary>
    public static bool RecoverIfNeeded() => Engine.RecoverIfNeeded();

    /// <summary>True while RIX owns the machine's proxy setting.</summary>
    public static bool Owned => Engine.HasBackup;
}

/// <summary>The real store: HKCU Internet Settings, plus the WinINET refresh.</summary>
public sealed class WinInetProxyStore : IProxyStore
{
    private const string KeyPath = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
    private const int InternetOptionRefresh = 37;
    private const int InternetOptionSettingsChanged = 39;

    public (bool Enabled, string Server, string Override) Read()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(KeyPath);
            if (key == null) return (false, "", "");
            var enabled = key.GetValue("ProxyEnable") is int i && i != 0;
            var server = key.GetValue("ProxyServer") as string ?? "";
            var over = key.GetValue("ProxyOverride") as string ?? "";
            return (enabled, server, over);
        }
        catch
        {
            return (false, "", "");
        }
    }

    public void Write(bool enabled, string server, string overrideValue)
    {
        using var key = Registry.CurrentUser.CreateSubKey(KeyPath, writable: true)
            ?? throw new InvalidOperationException("the proxy registry key could not be opened");

        key.SetValue("ProxyEnable", enabled ? 1 : 0, RegistryValueKind.DWord);
        key.SetValue("ProxyServer", server ?? "", RegistryValueKind.String);
        if (!string.IsNullOrEmpty(overrideValue))
            key.SetValue("ProxyOverride", overrideValue, RegistryValueKind.String);
        else
            key.DeleteValue("ProxyOverride", throwOnMissingValue: false);

        Refresh();
    }

    /// <summary>
    /// Tells running apps to re-read the settings. Without this, a browser that
    /// is already open keeps using the values it read when it started.
    /// </summary>
    private static void Refresh()
    {
        InternetSetOption(IntPtr.Zero, InternetOptionSettingsChanged, IntPtr.Zero, 0);
        InternetSetOption(IntPtr.Zero, InternetOptionRefresh, IntPtr.Zero, 0);
    }

    [DllImport("wininet.dll", SetLastError = true)]
    private static extern bool InternetSetOption(IntPtr session, int option, IntPtr buffer, int length);
}
