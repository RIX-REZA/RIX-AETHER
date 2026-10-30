using System.Runtime.InteropServices;
using System.Text.Json;
using Microsoft.Win32;

namespace Rix.Windows.Core;

/// <summary>
/// The Windows system proxy, which is how everything on the machine is carried
/// without a TUN device or a driver.
///
/// This is the honest description of the trade: a WinINET proxy setting covers
/// Edge, Chrome, Office and everything else that asks Windows where the proxy
/// is, but it does not cover Firefox (which keeps its own) or applications that
/// open their own sockets. A TUN front end would cover those and needs routes,
/// which on Windows means either sending every packet of the machine at a
/// userspace stack -- including the core's own, unless a kernel filter spares
/// them -- or shipping a WFP callout driver. Neither belongs in an installer
/// that has to be quick and stay working.
///
/// The registry lives under HKCU, so this needs no elevation, and it is
/// reverted on exit. The revert is also armed against a crash: the previous
/// values go into a lock file *before* the registry is touched, and the next
/// launch puts them back if the lock is still there.
/// </summary>
public static class SystemProxy
{
    private const string KeyPath = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";

    private const int InternetOptionRefresh = 37;
    private const int InternetOptionSettingsChanged = 39;

    /// <summary>
    /// What was there before. `Server` empty and `Enabled` false is the normal
    /// starting point, and is also what "no proxy configured" looks like.
    /// </summary>
    private sealed class Backup
    {
        public bool Enabled { get; set; }
        public string Server { get; set; } = "";
        public string Override { get; set; } = "";
    }

    private static string LockPath => Path.Combine(AppState.Dir, "proxy.lock");

    /// <summary>127.0.0.1:1819, or 1822 when Psiphon is the exit.</summary>
    public static string Server(int port) => "127.0.0.1:" + port;

    /// <summary>
    /// Sent straight out instead of into the tunnel: the loopback and the
    /// private ranges, so a LAN share or a local server keeps working while the
    /// tunnel is up.
    /// </summary>
    public const string Bypass =
        "<local>;localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.2*;" +
        "172.30.*;172.31.*;192.168.*;169.254.*;[::1]";

    /// <summary>Whether a proxy is set right now, and where it points.</summary>
    public static (bool Enabled, string Server) Current()
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(KeyPath);
            if (key == null) return (false, "");
            var enabled = key.GetValue("ProxyEnable") is int i && i != 0;
            var server = key.GetValue("ProxyServer") as string ?? "";
            return (enabled, server);
        }
        catch
        {
            return (false, "");
        }
    }

    /// <summary>
    /// Points the machine at the local proxy. Returns false when the registry
    /// refused, in which case nothing was half-written: the caller is told so it
    /// can say the proxy is not on rather than pretending.
    /// </summary>
    public static bool Enable(int port, out string error)
    {
        error = null;
        try
        {
            Directory.CreateDirectory(AppState.Dir);

            var (enabled, server) = Current();
            var previous = new Backup { Enabled = enabled, Server = server };
            using (var existing = Registry.CurrentUser.OpenSubKey(KeyPath))
            {
                previous.Override = existing?.GetValue("ProxyOverride") as string ?? "";
            }

            // Before, not after: a kill between these two lines leaves a lock
            // file, and a lock file with the old values is recoverable. The
            // reverse order would lose them.
            //
            // Written only when there is not one already, because a second
            // Enable would read back the values RIX itself set and then
            // "restore" the machine to a proxy that is no longer running.
            if (!File.Exists(LockPath))
            {
                File.WriteAllText(LockPath, JsonSerializer.Serialize(previous));
            }

            using var key = Registry.CurrentUser.CreateSubKey(KeyPath, writable: true);
            if (key == null)
            {
                error = "the proxy registry key could not be opened";
                File.Delete(LockPath);
                return false;
            }

            key.SetValue("ProxyEnable", 1, RegistryValueKind.DWord);
            key.SetValue("ProxyServer", Server(port), RegistryValueKind.String);
            key.SetValue("ProxyOverride", Bypass, RegistryValueKind.String);
            Refresh();
            return true;
        }
        catch (Exception ex)
        {
            error = ex.Message;
            try { File.Delete(LockPath); } catch { }
            return false;
        }
    }

    /// <summary>
    /// Puts back whatever <see cref="Enable"/> found.
    ///
    /// Does nothing at all without a lock file, which is the whole point: this
    /// is called on every exit path, including ones where the proxy was never
    /// turned on, and a machine whose owner runs their own proxy must not have
    /// it switched off by an app that had nothing to do with it.
    /// </summary>
    public static void Disable()
    {
        if (!File.Exists(LockPath)) return;

        try
        {
            using var key = Registry.CurrentUser.CreateSubKey(KeyPath, writable: true);
            if (key == null) return;

            var backup = ReadLock() ?? new Backup();
            key.SetValue("ProxyEnable", backup.Enabled ? 1 : 0, RegistryValueKind.DWord);
            // The value is left in place rather than deleted when it was absent
            // before: an empty ProxyServer with ProxyEnable off is what every
            // other tool leaves behind too, and deleting it can confuse a
            // machine where it was set by policy.
            key.SetValue("ProxyServer", backup.Server ?? "", RegistryValueKind.String);
            if (!string.IsNullOrEmpty(backup.Override))
                key.SetValue("ProxyOverride", backup.Override, RegistryValueKind.String);
            else
                key.DeleteValue("ProxyOverride", throwOnMissingValue: false);

            Refresh();
        }
        catch
        {
            // Nothing useful to do here; the lock file stays, so the next launch
            // tries again.
            return;
        }

        try { File.Delete(LockPath); } catch { }
    }

    /// <summary>
    /// Called once at startup. If a lock file survived, the last run was killed
    /// before it could revert, and the machine is still pointing at a proxy that
    /// is not running.
    /// </summary>
    public static bool RecoverIfNeeded()
    {
        if (!File.Exists(LockPath)) return false;
        try
        {
            Disable();
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static Backup ReadLock()
    {
        try
        {
            if (!File.Exists(LockPath)) return null;
            return JsonSerializer.Deserialize<Backup>(File.ReadAllText(LockPath));
        }
        catch
        {
            return null;
        }
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