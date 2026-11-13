using System.Net;
using Rix.Windows;
using Rix.Windows.Core;
using Xunit;

namespace Rix.Windows.Tests;

/// <summary>A probe with a scripted answer per address, no sockets involved.</summary>
public sealed class ScriptedProbe : IIpProbe
{
    private readonly Func<string, int, ProbeResult> _answer;
    public int Calls { get; private set; }

    public ScriptedProbe(Func<string, int, ProbeResult> answer) => _answer = answer;

    public Task<ProbeResult> ProbeAsync(string address, int port, IpScanOptions options, CancellationToken token)
    {
        Calls++;
        return Task.FromResult(_answer(address, port));
    }
}

public sealed class IpScoringTests
{
    [Fact]
    public void Score_is_bounded_and_prefers_a_healthy_address()
    {
        var good = IpScoring.Score(1.0, 20, 1, 2_000_000);
        var bad = IpScoring.Score(0.2, 700, 350, 0);

        Assert.InRange(good, 0, 1);
        Assert.InRange(bad, 0, 1);
        Assert.True(good > bad);
    }

    [Fact]
    public void Success_outweighs_raw_latency()
    {
        var reliable = IpScoring.Score(1.0, 120, 5, 0);
        var flaky = IpScoring.Score(0.5, 10, 5, 0);

        Assert.True(reliable > flaky);
    }

    [Fact]
    public void Score_never_reaches_one_for_an_imperfect_endpoint()
    {
        Assert.True(IpScoring.Score(1.0, 1, 0, IpScoring.SpeedCeilingBps) < 1.0);
        Assert.True(IpScoring.Score(0.9, 0, 0, IpScoring.SpeedCeilingBps) < 1.0);
    }
}

public sealed class CloudflareRangesTests
{
    [Fact]
    public void Built_in_ranges_are_official_shaped_cidrs()
    {
        var ranges = CloudflareRanges.BuiltIn();

        Assert.NotEmpty(ranges.V4);
        Assert.NotEmpty(ranges.V6);
        foreach (var cidr in ranges.V4.Concat(ranges.V6))
        {
            var parts = cidr.Split('/');
            Assert.Equal(2, parts.Length);
            Assert.True(IPAddress.TryParse(parts[0], out _));
            Assert.True(int.TryParse(parts[1], out var prefix));
            Assert.True(prefix > 0);
        }
    }

    [Fact]
    public void Candidate_enumeration_is_bounded_and_deterministic()
    {
        var ranges = CloudflareRanges.BuiltIn();
        var options = new IpScanOptions
        {
            AddressesPerRange = 2,
            Seeds = Array.Empty<string>(),
            IncludeIpv6 = false,
        };

        var first = ranges.CandidateAddresses(options).ToList();
        var second = ranges.CandidateAddresses(options).ToList();

        Assert.Equal(ranges.V4.Count * 2, first.Count);
        Assert.Equal(first, second);
        Assert.Equal(first.Count, first.Distinct().Count());
    }

    [Fact]
    public void Ipv6_is_only_enumerated_when_asked_for()
    {
        var ranges = CloudflareRanges.BuiltIn();
        var without = ranges.CandidateAddresses(new IpScanOptions
        {
            AddressesPerRange = 1, Seeds = Array.Empty<string>(), IncludeIpv6 = false,
        }).ToList();
        var with = ranges.CandidateAddresses(new IpScanOptions
        {
            AddressesPerRange = 1, Seeds = Array.Empty<string>(), IncludeIpv6 = true,
        }).ToList();

        Assert.Contains(without, a => IPAddress.Parse(a).AddressFamily == System.Net.Sockets.AddressFamily.InterNetwork);
        Assert.Contains(with, a => IPAddress.Parse(a).AddressFamily == System.Net.Sockets.AddressFamily.InterNetworkV6);
        Assert.True(with.Count > without.Count);
    }

    [Fact]
    public void Cache_round_trips_through_disk()
    {
        var previous = AppState.Dir;
        AppState.Dir = Path.Combine(Path.GetTempPath(), "rix-range-" + Guid.NewGuid().ToString("N"));
        try
        {
            var ranges = CloudflareRanges.BuiltIn();
            ranges.FetchedAt = new DateTime(2026, 1, 2, 3, 4, 5, DateTimeKind.Utc);
            CloudflareRanges.WriteCache(ranges);

            var read = CloudflareRanges.ReadCache();
            Assert.NotNull(read);
            Assert.Equal(ranges.V4, read.V4);
            Assert.Equal(ranges.FetchedAt, read.FetchedAt);
        }
        finally
        {
            try { Directory.Delete(AppState.Dir, true); } catch { }
            AppState.Dir = previous;
        }
    }
}

public sealed class IpScannerTests
{
    private static CloudflareRanges OneRange() => new()
    {
        V4 = new List<string> { "192.0.2.0/24" },
        V6 = new List<string>(),
    };

    [Fact]
    public async Task A_scan_ranks_only_reachable_addresses()
    {
        var options = new IpScanOptions
        {
            Ports = new[] { 443 },
            Samples = 2,
            AddressesPerRange = 6,
            Seeds = Array.Empty<string>(),
            Concurrency = 4,
            MeasureSpeed = true,
        };
        var ranges = OneRange();
        var reachable = ranges.CandidateAddresses(options).First();
        var probe = new ScriptedProbe((address, port) =>
            address == reachable && port == 443
                ? new ProbeResult(true, 30, 500_000, 4096)
                : ProbeResult.Failed);

        var scanner = new IpScanner(probe);
        var results = await scanner.ScanAsync(options, ranges, null, CancellationToken.None);

        var only = Assert.Single(results);
        Assert.Equal(reachable, only.Address);
        Assert.Equal(443, only.Port);
        Assert.Equal(1.0, only.SuccessRate, 3);
        Assert.Equal(30, only.LatencyMs, 3);
    }

    [Fact]
    public async Task A_port_that_answers_is_recorded_even_when_443_does_not()
    {
        var probe = new ScriptedProbe((address, port) =>
            port == 2053 ? new ProbeResult(true, 80, 0, 0) : ProbeResult.Failed);

        var scanner = new IpScanner(probe);
        var options = new IpScanOptions
        {
            Ports = new[] { 443, 2053 },
            Samples = 1,
            AddressesPerRange = 2,
            Seeds = Array.Empty<string>(),
            Concurrency = 2,
            MeasureSpeed = false,
        };

        var results = await scanner.ScanAsync(options, OneRange(), null, CancellationToken.None);

        Assert.Equal(2, results.Count);
        Assert.All(results, r => Assert.Equal(2053, r.Port));
    }

    [Fact]
    public async Task A_success_rate_reflects_the_samples_taken()
    {
        var call = 0;
        var probe = new ScriptedProbe((_, _) =>
            Interlocked.Increment(ref call) % 2 == 1
                ? new ProbeResult(true, 50, 0, 0)
                : ProbeResult.Failed);

        var scanner = new IpScanner(probe);
        var options = new IpScanOptions
        {
            Ports = new[] { 443 },
            Samples = 2,
            AddressesPerRange = 1,
            Seeds = Array.Empty<string>(),
            Concurrency = 1,
            MeasureSpeed = false,
        };

        var results = await scanner.ScanAsync(options, OneRange(), null, CancellationToken.None);

        var result = Assert.Single(results);
        Assert.Equal(0.5, result.SuccessRate, 3);
        Assert.Equal(2, result.Attempts);
    }

    [Fact]
    public async Task Progress_is_reported_and_finishes_at_the_total()
    {
        var reports = new List<ScanProgress>();
        var scanner = new IpScanner(new ScriptedProbe((_, _) => new ProbeResult(true, 10, 0, 0)));
        var options = new IpScanOptions
        {
            Ports = new[] { 443 },
            Samples = 1,
            AddressesPerRange = 4,
            Seeds = Array.Empty<string>(),
            Concurrency = 1,
            MeasureSpeed = false,
        };

        await scanner.ScanAsync(options, OneRange(),
            new Progress<ScanProgress>(p => { lock (reports) reports.Add(p); }), CancellationToken.None);

        // Progress<T> posts to the thread pool; give it a moment to drain.
        await Task.Delay(100);
        lock (reports)
        {
            Assert.NotEmpty(reports);
            Assert.Contains(reports, r => r.Done == r.Total && r.Total == 4);
        }
    }
}
