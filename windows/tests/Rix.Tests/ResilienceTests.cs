using Rix.Windows.Core;
using Xunit;

namespace Rix.Windows.Tests;

public sealed class PeerPoolTests
{
    private static ScanCandidate Candidate(string name, double score) =>
        new() { Address = name, Port = 443, Score = score };

    private static DateTime Now = new(2026, 1, 1, 0, 0, 0, DateTimeKind.Utc);

    private PeerPool Pool(params ScanCandidate[] ranked) =>
        new(() => Now, TimeSpan.FromSeconds(30), TimeSpan.FromMinutes(10));

    [Fact]
    public void The_best_scored_peer_is_picked_first()
    {
        var pool = Pool();
        pool.Replace(new[] { Candidate("a", 0.5), Candidate("b", 0.9), Candidate("c", 0.7) });

        Assert.Equal("b:443", pool.Pick().Endpoint);
    }

    [Fact]
    public void A_failed_peer_is_rotated_away_and_cooled_down()
    {
        var pool = Pool();
        pool.Replace(new[] { Candidate("a", 0.9), Candidate("b", 0.5) });

        Assert.Equal("a:443", pool.Pick().Endpoint);
        pool.ReportFailure("a:443");

        // Immediately after a failure, the next candidate is offered instead.
        Assert.Equal("b:443", pool.Pick().Endpoint);

        // After the cooldown the failed peer is offered again.
        Now = Now.AddMinutes(1);
        Assert.Equal("a:443", pool.Pick().Endpoint);
    }

    [Fact]
    public void Repeated_failures_grow_the_cooldown()
    {
        var pool = Pool();
        pool.Replace(new[] { Candidate("a", 0.9), Candidate("b", 0.5) });

        pool.Pick();
        pool.ReportFailure("a:443");      // cooldown 1: 30s
        Now = Now.AddSeconds(31);
        Assert.Equal("a:443", pool.Pick().Endpoint);
        pool.ReportFailure("a:443");      // cooldown 2: 60s

        Now = Now.AddSeconds(31);
        Assert.NotEqual("a:443", pool.Pick().Endpoint); // still cooling

        Now = Now.AddSeconds(31);
        Assert.Equal("a:443", pool.Pick().Endpoint);
    }

    [Fact]
    public void Success_clears_the_debt()
    {
        var pool = Pool();
        pool.Replace(new[] { Candidate("a", 0.9), Candidate("b", 0.5) });

        pool.Pick();
        pool.ReportFailure("a:443");
        pool.ReportSuccess("a:443");

        Assert.Equal("a:443", pool.Pick().Endpoint);
    }

    [Fact]
    public void A_fully_cooling_pool_still_offers_something()
    {
        var pool = Pool();
        pool.Replace(new[] { Candidate("a", 0.9), Candidate("b", 0.5) });

        pool.ReportFailure("a:443");
        pool.ReportFailure("b:443");

        // A refusal would stall reconnects entirely; the pool offers the
        // least-bad candidate and lets the supervisor's own timeout bound it.
        Assert.NotNull(pool.Pick());
    }

    [Fact]
    public void Replacement_keeps_failure_state_per_endpoint()
    {
        var pool = Pool();
        pool.Replace(new[] { Candidate("a", 0.9), Candidate("b", 0.5) });
        pool.ReportFailure("a:443");

        // A new scan arrives, still ranking a:1 on top.
        pool.Replace(new[] { Candidate("a", 0.95), Candidate("b", 0.4) });

        // The failure survives the refresh: a:1 must not be re-offered while
        // it is cooling down.
        Assert.Equal("b:443", pool.Pick().Endpoint);
    }

    [Fact]
    public void An_empty_pool_returns_null_and_the_supervisor_tolerates_it()
    {
        var pool = Pool();
        Assert.Null(pool.Pick());
    }
}

public sealed class ResilienceSupervisorTests
{
    private static CoreSupervisorOptions Fast() => new()
    {
        HealthInterval = TimeSpan.FromMilliseconds(5),
        DegradedAfter = 1,
        ReconnectAfter = 2,
        ConnectTimeout = TimeSpan.FromSeconds(2),
        MaxReconnects = 0, // never give up: the resilience profile
        FastRetryCount = 2,
        VerifyTimeout = TimeSpan.FromSeconds(1),
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
    public async Task A_failing_peer_is_rotated_until_one_verifies()
    {
        var pool = new PeerPool();
        pool.Replace(new[]
        {
            new ScanCandidate { Address = "192.0.2.10", Port = 443, Score = 0.9, SuccessRate = 1 },
            new ScanCandidate { Address = "192.0.2.11", Port = 443, Score = 0.8, SuccessRate = 1 },
            new ScanCandidate { Address = "192.0.2.12", Port = 443, Score = 0.7, SuccessRate = 1 },
        });

        var verified = new List<string>();
        var rejected = new List<string>();
        var path = new ScriptedPath(true, true, true, true, true, true);

        var supervisor = new CoreSupervisor(
            _ => Task.FromResult<IConnectionPath>(path), Fast(),
            new BackoffPolicy(random: () => 0.5), Instant)
        {
            BestAddress = () => pool.Pick(),
            PreVerify = candidate =>
            {
                // The two best peers do not answer a direct probe; the third
                // does. The core must end up on the third, not the first.
                var ok = candidate.Address == "192.0.2.12";
                verified.Add(candidate.Endpoint);
                return Task.FromResult(ok);
            },
            // Like the app's wiring: a rejection cools the peer down in the
            // pool, which is what makes the next pick rotate away from it.
            CandidateRejected = candidate =>
            {
                rejected.Add(candidate.Endpoint);
                pool.ReportFailure(candidate.Endpoint);
            },
        };

        supervisor.Start();
        await WaitFor(() => supervisor.Status.State == RixState.Connected);
        await supervisor.StopAsync();

        Assert.Equal("192.0.2.10:443", verified[0]);
        Assert.Equal("192.0.2.11:443", verified[1]);
        Assert.Equal("192.0.2.12:443", verified[2]);
        Assert.Contains("192.0.2.10:443", rejected);
        Assert.Equal("192.0.2.12:443", pool.Pick()?.Endpoint);
    }

    /// <summary>A path whose connect never succeeds, so the supervisor stays
    /// in its retry cycle for as long as it is asked to.</summary>
    private sealed class FailingPath : IConnectionPath
    {
        public Task<bool> ConnectAsync(CancellationToken token) => Task.FromResult(false);
        public Task<bool> HealthAsync(CancellationToken token) => Task.FromResult(false);
        public Task DisconnectAsync() => Task.CompletedTask;
        public void Dispose() { }
    }

    [Fact]
    public async Task Unlimited_retries_never_give_up()
    {
        var created = 0;
        var supervisor = new CoreSupervisor(
            _ =>
            {
                Interlocked.Increment(ref created);
                return Task.FromResult<IConnectionPath>(new FailingPath());
            }, Fast(), new BackoffPolicy(TimeSpan.FromMilliseconds(5), TimeSpan.FromMilliseconds(20),
                () => 0.5), Instant);

        supervisor.Start();
        // Four attempts is beyond MaxReconnects=3's give-up point; with
        // unlimited retries the supervisor is still trying instead of parking.
        await WaitFor(() => Volatile.Read(ref created) >= 4, 6000);
        Assert.True(supervisor.IsRunning, "the supervisor must not give up on its own");

        await supervisor.StopAsync();
        Assert.Equal(RixState.Disconnected, supervisor.Status.State);
    }
}
