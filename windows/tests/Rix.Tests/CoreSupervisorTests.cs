using Rix.Windows.Core;
using Xunit;

namespace Rix.Windows.Tests;

/// <summary>A path whose answers come from a queue, so a reconnect is visible.</summary>
public sealed class ScriptedPath : IConnectionPath
{
    private readonly Queue<bool> _health;
    public bool Connected { get; private set; }
    public int ConnectCalls { get; private set; }
    public int DisconnectCalls { get; private set; }
    public bool Disposed { get; private set; }

    public ScriptedPath(params bool[] health) => _health = new Queue<bool>(health);

    public Task<bool> ConnectAsync(CancellationToken token)
    {
        ConnectCalls++;
        Connected = true;
        return Task.FromResult(true);
    }

    public Task<bool> HealthAsync(CancellationToken token)
    {
        lock (_health)
        {
            // A queue that runs dry stays unhealthy: the test wants a stable
            // answer, not a random one.
            return Task.FromResult(_health.Count > 0 && _health.Dequeue());
        }
    }

    public Task DisconnectAsync()
    {
        DisconnectCalls++;
        Connected = false;
        return Task.CompletedTask;
    }

    public void Dispose() => Disposed = true;
}

public sealed class BackoffTests
{
    [Fact]
    public void Backoff_grows_but_stays_under_the_ceiling()
    {
        var policy = new BackoffPolicy(TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(30), () => 1.0);

        Assert.Equal(1000, policy.Next(1).TotalMilliseconds, 0);
        Assert.Equal(2000, policy.Next(2).TotalMilliseconds, 0);
        Assert.Equal(4000, policy.Next(3).TotalMilliseconds, 0);

        for (var i = 1; i < 20; i++)
            Assert.InRange(policy.Next(i).TotalMilliseconds, 250, 30_000);
    }

    [Fact]
    public void Jitter_keeps_a_floor_and_never_exceeds_the_exponential_ceiling()
    {
        var low = new BackoffPolicy(TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(60), () => 0.0);
        var high = new BackoffPolicy(TimeSpan.FromSeconds(1), TimeSpan.FromSeconds(60), () => 0.999);

        Assert.Equal(250, low.Next(1).TotalMilliseconds, 0);
        Assert.InRange(high.Next(1).TotalMilliseconds, 900, 1000);
        Assert.InRange(high.Next(5).TotalMilliseconds, 15_000, 16_000);
    }
}

public sealed class CoreSupervisorTests
{
    private static CoreSupervisorOptions Fast() => new()
    {
        HealthInterval = TimeSpan.FromMilliseconds(5),
        DegradedAfter = 1,
        ReconnectAfter = 2,
        ConnectTimeout = TimeSpan.FromSeconds(2),
        MaxReconnects = 3,
    };

    private static Func<TimeSpan, CancellationToken, Task> Instant =>
        (_, token) => Task.Delay(1, token);

    private static async Task WaitFor(Func<bool> condition, int timeoutMs = 4000)
    {
        var deadline = DateTime.UtcNow.AddMilliseconds(timeoutMs);
        while (DateTime.UtcNow < deadline)
        {
            if (condition()) return;
            await Task.Delay(10);
        }
        throw new TimeoutException("condition was not met");
    }

    [Fact]
    public async Task A_healthy_path_ends_in_Connected()
    {
        var path = new ScriptedPath(true, true, true, true, true, true, true, true);
        var supervisor = new CoreSupervisor(_ => Task.FromResult<IConnectionPath>(path), Fast(),
            new BackoffPolicy(random: () => 0.5), Instant);

        supervisor.Start();
        await WaitFor(() => supervisor.Status.State == RixState.Connected);

        await supervisor.StopAsync();
        Assert.Equal(RixState.Disconnected, supervisor.Status.State);
        Assert.True(path.DisconnectCalls >= 1);
    }

    [Fact]
    public async Task Repeated_health_failures_degrade_and_then_reconnect()
    {
        // Two failures reach ReconnectAfter=2, so a second path is created.
        var first = new ScriptedPath(false, false, false);
        var second = new ScriptedPath(true, true, true, true, true, true);
        var created = 0;
        var states = new List<RixState>();
        var supervisor = new CoreSupervisor(_ =>
        {
            var path = Interlocked.Increment(ref created) == 1 ? (IConnectionPath)first : second;
            return Task.FromResult(path);
        }, Fast(), new BackoffPolicy(random: () => 0.5), Instant);

        supervisor.Changed += status =>
        {
            lock (states) states.Add(status.State);
        };

        supervisor.Start();
        await WaitFor(() => Volatile.Read(ref created) >= 2);
        await WaitFor(() => supervisor.Status.State == RixState.Connected);

        lock (states)
        {
            Assert.Contains(RixState.Degraded, states);
            Assert.Contains(RixState.Reconnecting, states);
        }

        Assert.True(first.DisconnectCalls >= 1);
        await supervisor.StopAsync();
    }

    [Fact]
    public async Task A_network_change_marks_the_path_for_replacement()
    {
        var first = new ScriptedPath(Enumerable.Repeat(true, 100).ToArray());
        var second = new ScriptedPath(Enumerable.Repeat(true, 100).ToArray());
        var created = 0;
        var supervisor = new CoreSupervisor(_ =>
        {
            var path = Interlocked.Increment(ref created) == 1 ? (IConnectionPath)first : second;
            return Task.FromResult(path);
        }, Fast(), new BackoffPolicy(random: () => 0.5), Instant);

        supervisor.Start();
        await WaitFor(() => supervisor.Status.State == RixState.Connected);

        supervisor.NetworkChanged("Wi-Fi changed");
        await WaitFor(() => created >= 2);
        await supervisor.StopAsync();
    }

    [Fact]
    public async Task Failover_asks_for_the_best_scanned_address()
    {
        var asked = 0;
        var supervisor = new CoreSupervisor(_ => Task.FromResult<IConnectionPath>(
                new ScriptedPath(false, false, false, false)),
            Fast(), new BackoffPolicy(random: () => 0.5), Instant)
        {
            BestAddress = () =>
            {
                Interlocked.Increment(ref asked);
                return new ScanCandidate { Address = "192.0.2.1", Port = 443, Score = 1 };
            },
        };

        supervisor.Start();
        await WaitFor(() => Volatile.Read(ref asked) >= 2);
        await supervisor.StopAsync();

        Assert.True(asked >= 1);
    }

    [Fact]
    public async Task Stop_before_any_connect_is_a_no_op_not_a_crash()
    {
        var supervisor = new CoreSupervisor(_ => Task.FromResult<IConnectionPath>(new ScriptedPath()));
        await supervisor.StopAsync();
        Assert.Equal(RixState.Disconnected, supervisor.Status.State);
    }
}
