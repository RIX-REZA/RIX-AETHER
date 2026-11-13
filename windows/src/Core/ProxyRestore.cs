using System.Text.Json;

namespace Rix.Windows.Core;

/// <summary>
/// The proxy values that existed before RIX changed anything.
///
/// A POCO on purpose: it is serialised into the lock file that survives a
/// crash, so its field names are part of an on-disk format. Do not rename them
/// without a migration.
/// </summary>
public sealed class ProxyBackup
{
    public bool Enabled { get; set; }
    public string Server { get; set; } = "";
    public string Override { get; set; } = "";
}

/// <summary>
/// The narrow slice of the machine the restore logic needs: where the proxy
/// currently points, and how to write it back.
///
/// Extracted so the backup/restore rules can be tested without Windows
/// registry access. The production implementation is
/// <see cref="WinInetProxyStore"/>; the tests use an in-memory one.
/// </summary>
public interface IProxyStore
{
    (bool Enabled, string Server, string Override) Read();

    /// <summary>Writes the values. Empty <paramref name="server"/> is written
    /// as an empty string, not deleted: that is what Windows itself leaves
    /// behind when proxying is turned off.</summary>
    void Write(bool enabled, string server, string overrideValue);
}

/// <summary>
/// Enable / disable / recover, over any <see cref="IProxyStore"/>.
///
/// The rule that matters: the previous values are written to the lock file
/// *before* the store is touched, and the lock file is only written when one is
/// not already there. A second Enable must never overwrite the backup with
/// RIX's own proxy address, or Disable would "restore" the machine to a proxy
/// that is no longer running.
/// </summary>
public sealed class ProxyRestore
{
    private static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    private readonly IProxyStore _store;
    private readonly string _lockPath;

    public ProxyRestore(IProxyStore store, string lockPath)
    {
        _store = store;
        _lockPath = lockPath;
    }

    public string LockPath => _lockPath;

    public bool HasBackup => File.Exists(_lockPath);

    /// <summary>
    /// Points the machine at <paramref name="port"/>. Returns false when the
    /// write failed, and leaves no lock file behind that this call created.
    /// </summary>
    public bool Enable(int port, out string error)
    {
        error = null;
        // Whether *this* call created the lock. A lock found already on disk is
        // the previous run's record and must survive even a failed write.
        var wroteLock = false;
        try
        {
            var directory = Path.GetDirectoryName(_lockPath);
            if (!string.IsNullOrEmpty(directory)) Directory.CreateDirectory(directory);

            if (!File.Exists(_lockPath))
            {
                var (enabled, server, over) = _store.Read();
                var backup = new ProxyBackup { Enabled = enabled, Server = server, Override = over };
                // Mark first: a half-written lock is worse than none, because
                // Disable would read it, fail, and fall back to defaults over
                // the user's real settings.
                wroteLock = true;
                File.WriteAllText(_lockPath, JsonSerializer.Serialize(backup, Json));
            }

            _store.Write(true, "127.0.0.1:" + port, ProxyRestore.Bypass);
            return true;
        }
        catch (Exception ex)
        {
            error = ex.Message;
            if (wroteLock)
            {
                try { File.Delete(_lockPath); } catch { }
            }
            return false;
        }
    }

    /// <summary>Puts back what Enable found. No lock file means no-op.</summary>
    public void Disable()
    {
        if (!File.Exists(_lockPath)) return;

        var backup = ReadBackup();
        if (backup == null)
        {
            // An unreadable lock is still evidence that RIX owned the setting.
            // Turning the proxy off is the safe direction: a dead local proxy
            // breaks every browser, while no proxy merely means direct traffic.
            backup = new ProxyBackup { Enabled = false, Server = "" };
        }

        try
        {
            _store.Write(backup.Enabled, backup.Server ?? "", backup.Override ?? "");
        }
        catch
        {
            // Leave the lock in place so the next launch retries.
            return;
        }

        try { File.Delete(_lockPath); } catch { }
    }

    public bool RecoverIfNeeded()
    {
        if (!File.Exists(_lockPath)) return false;
        Disable();
        return !File.Exists(_lockPath);
    }

    public ProxyBackup ReadBackup()
    {
        try
        {
            if (!File.Exists(_lockPath)) return null;
            return JsonSerializer.Deserialize<ProxyBackup>(File.ReadAllText(_lockPath), Json);
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// Loopback and private ranges go straight out, so a LAN share or a local
    /// server keeps working while the tunnel is up.
    /// </summary>
    public const string Bypass =
        "<local>;localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.2*;" +
        "172.30.*;172.31.*;192.168.*;169.254.*;[::1]";
}
