using Rix.Windows.Core;
using Xunit;

namespace Rix.Windows.Tests;

/// <summary>
/// An in-memory stand-in for HKCU Internet Settings. It also lets a test make
/// the write fail at a chosen moment, which is the case the lock-file rules
/// exist for.
/// </summary>
public sealed class FakeProxyStore : IProxyStore
{
    public bool Enabled { get; private set; }
    public string Server { get; private set; } = "";
    public string Override { get; private set; } = "";
    public int Writes { get; private set; }
    public bool FailWrites { get; set; }

    public FakeProxyStore(bool enabled = false, string server = "", string over = "")
    {
        Enabled = enabled;
        Server = server;
        Override = over;
    }

    public (bool Enabled, string Server, string Override) Read() => (Enabled, Server, Override);

    public void Write(bool enabled, string server, string overrideValue)
    {
        Writes++;
        if (FailWrites) throw new IOException("registry denied");
        Enabled = enabled;
        Server = server;
        Override = overrideValue;
    }
}

public sealed class ProxyRestoreTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "rix-proxy-" + Guid.NewGuid().ToString("N"));

    private string LockPath => Path.Combine(_dir, "proxy.lock");

    public ProxyRestoreTests() => Directory.CreateDirectory(_dir);

    public void Dispose()
    {
        try { Directory.Delete(_dir, true); } catch { }
    }

    [Fact]
    public void Enable_backs_up_the_users_own_proxy_and_disable_puts_it_back()
    {
        var store = new FakeProxyStore(true, "corp-proxy:8080", "*.internal");
        var engine = new ProxyRestore(store, LockPath);

        Assert.True(engine.Enable(1819, out var error), error);
        Assert.Equal("127.0.0.1:1819", store.Server);
        Assert.Equal(ProxyRestore.Bypass, store.Override);
        Assert.True(File.Exists(LockPath));

        engine.Disable();

        Assert.True(store.Enabled);
        Assert.Equal("corp-proxy:8080", store.Server);
        Assert.Equal("*.internal", store.Override);
        Assert.False(File.Exists(LockPath));
    }

    [Fact]
    public void Enable_twice_does_not_replace_the_original_backup()
    {
        var store = new FakeProxyStore(false, "", "");
        var engine = new ProxyRestore(store, LockPath);

        Assert.True(engine.Enable(1819, out _));
        Assert.True(engine.Enable(1822, out _));

        // The machine now points at the second port, but the backup still says
        // "there was no proxy" -- not "there was a proxy at 1819".
        Assert.Equal("127.0.0.1:1822", store.Server);
        engine.Disable();
        Assert.False(store.Enabled);
        Assert.Equal("", store.Server);
    }

    [Fact]
    public void Disable_without_a_lock_file_changes_nothing()
    {
        var store = new FakeProxyStore(true, "keep-me:3128", "");
        var engine = new ProxyRestore(store, LockPath);

        engine.Disable();

        Assert.Equal(0, store.Writes);
        Assert.True(store.Enabled);
        Assert.Equal("keep-me:3128", store.Server);
    }

    [Fact]
    public void A_failed_write_leaves_no_lock_that_this_call_created()
    {
        var store = new FakeProxyStore(false, "", "") { FailWrites = true };
        var engine = new ProxyRestore(store, LockPath);

        Assert.False(engine.Enable(1819, out var error));
        Assert.False(string.IsNullOrWhiteSpace(error));
        Assert.False(File.Exists(LockPath));
    }

    [Fact]
    public void A_failed_write_keeps_a_backup_that_was_already_there()
    {
        var store = new FakeProxyStore(true, "old:1", "old");
        var engine = new ProxyRestore(store, LockPath);
        File.WriteAllText(LockPath, "{\"enabled\":true,\"server\":\"old:1\",\"override\":\"old\"}");

        store.FailWrites = true;
        Assert.False(engine.Enable(1819, out _));

        // The previous run's record is the only copy of the user's settings.
        Assert.True(File.Exists(LockPath));
    }

    [Fact]
    public void Recovery_restores_after_a_crash()
    {
        var store = new FakeProxyStore(false, "", "");
        var engine = new ProxyRestore(store, LockPath);

        // Simulate a killed process: the lock is present, the machine still
        // points at RIX's now-dead port, and no Disable ran.
        engine.Enable(1819, out _);
        Assert.True(store.Enabled);

        var next = new ProxyRestore(store, LockPath);
        Assert.True(next.HasBackup);
        Assert.True(next.RecoverIfNeeded());

        Assert.False(store.Enabled);
        Assert.False(next.HasBackup);
        Assert.False(next.RecoverIfNeeded());
    }

    [Fact]
    public void An_unreadable_lock_turns_the_proxy_off_instead_of_leaving_it_broken()
    {
        var store = new FakeProxyStore(true, "127.0.0.1:1819", "");
        var engine = new ProxyRestore(store, LockPath);
        File.WriteAllText(LockPath, "{ not json");

        engine.Disable();

        Assert.False(store.Enabled);
        Assert.False(File.Exists(LockPath));
    }
}
