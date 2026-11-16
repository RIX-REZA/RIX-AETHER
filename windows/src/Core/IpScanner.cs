using System.Collections.Concurrent;
using System.Diagnostics;
using System.Numerics;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Text.Json;

namespace Rix.Windows;

/// <summary>
/// The Cloudflare edge scanner.
///
/// The tunnel must not be chained to one hard-coded address. This layer fetches
/// Cloudflare's published ranges, keeps them in a cache that survives restarts,
/// probes candidate addresses directly (never through the tunnel), ranks them,
/// and hands the best one to the core as <c>--peer</c>.
///
/// The probe is deliberately modest: a TCP connect, a TLS handshake and a tiny
/// HTTPS request to Cloudflare's own speed endpoint. That is enough to tell a
/// reachable edge from a black hole, and it produces the four numbers the
/// screen shows -- latency, success rate, jitter and a small throughput sample.
/// It is not a bandwidth benchmark, and the UI does not present it as one.
/// </summary>
public sealed record IpEndpoint(string Address, int Port);

/// <summary>
/// A bundled source of 200 real Cloudflare edge addresses, spread across all
/// of Cloudflare's published IPv4 ranges, plus the six best-known anycast
/// addresses. It is carried in the app so a scan never depends on reaching
/// cloudflare.com's list endpoint: the scan probes these addresses directly,
/// and when no address answers, one of them is picked at random for the
/// connection instead of pinning a single hard-coded IP.
/// </summary>
public static class CloudflareFallbackIps
{
    public static readonly string[] Pool =
    {
        "188.114.96.1",
        "162.159.192.1",
        "172.64.0.1",
        "104.16.0.1",
        "1.0.0.1",
        "1.1.1.1",
        "173.245.48.3",
        "173.245.49.61",
        "173.245.50.119",
        "173.245.51.177",
        "173.245.52.235",
        "173.245.54.37",
        "173.245.55.95",
        "173.245.56.153",
        "173.245.57.211",
        "173.245.59.13",
        "173.245.60.71",
        "173.245.61.129",
        "173.245.62.187",
        "103.21.244.3",
        "103.21.244.81",
        "103.21.244.159",
        "103.21.244.237",
        "103.21.245.59",
        "103.21.245.137",
        "103.21.245.215",
        "103.21.246.37",
        "103.21.246.115",
        "103.21.246.193",
        "103.21.247.15",
        "103.21.247.93",
        "103.21.247.171",
        "103.22.200.3",
        "103.22.200.81",
        "103.22.200.159",
        "103.22.200.237",
        "103.22.201.59",
        "103.22.201.137",
        "103.22.201.215",
        "103.22.202.37",
        "103.22.202.115",
        "103.22.202.193",
        "103.22.203.15",
        "103.22.203.93",
        "103.22.203.171",
        "103.31.4.3",
        "103.31.4.81",
        "103.31.4.159",
        "103.31.4.237",
        "103.31.5.59",
        "103.31.5.137",
        "103.31.5.215",
        "103.31.6.37",
        "103.31.6.115",
        "103.31.6.193",
        "103.31.7.15",
        "103.31.7.93",
        "103.31.7.171",
        "141.101.64.3",
        "141.101.68.238",
        "141.101.73.217",
        "141.101.78.196",
        "141.101.83.175",
        "141.101.88.154",
        "141.101.93.133",
        "141.101.98.112",
        "141.101.103.91",
        "141.101.108.70",
        "141.101.113.49",
        "141.101.118.28",
        "141.101.123.7",
        "108.162.192.3",
        "108.162.196.238",
        "108.162.201.217",
        "108.162.206.196",
        "108.162.211.175",
        "108.162.216.154",
        "108.162.221.133",
        "108.162.226.112",
        "108.162.231.91",
        "108.162.236.70",
        "108.162.241.49",
        "108.162.246.28",
        "108.162.251.7",
        "190.93.240.3",
        "190.93.241.61",
        "190.93.242.119",
        "190.93.243.177",
        "190.93.244.235",
        "190.93.246.37",
        "190.93.247.95",
        "190.93.248.153",
        "190.93.249.211",
        "190.93.251.13",
        "190.93.252.71",
        "190.93.253.129",
        "190.93.254.187",
        "188.114.96.3",
        "188.114.97.61",
        "188.114.98.119",
        "188.114.99.177",
        "188.114.100.235",
        "188.114.102.37",
        "188.114.103.95",
        "188.114.104.153",
        "188.114.105.211",
        "188.114.107.13",
        "188.114.108.71",
        "188.114.109.129",
        "188.114.110.187",
        "197.234.240.3",
        "197.234.240.81",
        "197.234.240.159",
        "197.234.240.237",
        "197.234.241.59",
        "197.234.241.137",
        "197.234.241.215",
        "197.234.242.37",
        "197.234.242.115",
        "197.234.242.193",
        "197.234.243.15",
        "197.234.243.93",
        "197.234.243.171",
        "198.41.128.3",
        "198.41.137.219",
        "198.41.147.179",
        "198.41.157.139",
        "198.41.167.99",
        "198.41.177.59",
        "198.41.187.19",
        "198.41.196.235",
        "198.41.206.195",
        "198.41.216.155",
        "198.41.226.115",
        "198.41.236.75",
        "198.41.246.35",
        "162.158.0.3",
        "162.158.39.101",
        "162.158.78.199",
        "162.158.118.41",
        "162.158.157.139",
        "162.158.196.237",
        "162.158.236.79",
        "162.159.19.177",
        "162.159.59.19",
        "162.159.98.117",
        "162.159.137.215",
        "162.159.177.57",
        "162.159.216.155",
        "104.16.0.3",
        "104.16.157.140",
        "104.17.59.21",
        "104.17.216.158",
        "104.18.118.39",
        "104.19.19.176",
        "104.19.177.57",
        "104.20.78.194",
        "104.20.236.75",
        "104.21.137.212",
        "104.22.39.93",
        "104.22.196.230",
        "104.23.98.111",
        "104.24.0.3",
        "104.24.78.199",
        "104.24.157.139",
        "104.24.236.79",
        "104.25.59.19",
        "104.25.137.215",
        "104.25.216.155",
        "104.26.39.95",
        "104.26.118.35",
        "104.26.196.231",
        "104.27.19.171",
        "104.27.98.111",
        "104.27.177.51",
        "172.64.0.3",
        "172.64.157.140",
        "172.65.59.21",
        "172.65.216.158",
        "172.66.118.39",
        "172.67.19.176",
        "172.67.177.57",
        "172.68.78.194",
        "172.68.236.75",
        "172.69.137.212",
        "172.70.39.93",
        "172.70.196.230",
        "172.71.98.111",
        "131.0.72.3",
        "131.0.72.81",
        "131.0.72.159",
        "131.0.72.237",
        "131.0.73.59",
        "131.0.73.137",
        "131.0.73.215",
        "131.0.74.37",
        "131.0.74.115",
        "131.0.74.193",
        "131.0.75.15",
        "131.0.75.93"
    };

    /// <summary>A different address on each call, so launches do not pin one
    /// IP when the scan could not pick a verified winner.</summary>
    public static string Random()
    {
        var i = System.Random.Shared.Next(Pool.Length);
        return Pool[i];
    }
}

public sealed class IpScanOptions
{
    /// <summary>Endpoints probed at the same time. Higher is faster and noisier.</summary>
    public int Concurrency { get; set; } = 96;

    /// <summary>Per-attempt limit for connect + TLS + the first bytes.</summary>
    public int TimeoutMs { get; set; } = 1500;

    /// <summary>How many times each address is tried, for success rate and jitter.</summary>
    public int Samples { get; set; } = 1;

    /// <summary>The ports Cloudflare serves the edge on.</summary>
    public int[] Ports { get; set; } = { 443, 2053, 2083, 2087, 2096, 8443 };

    public bool IncludeIpv6 { get; set; }

    /// <summary>Addresses sampled from each published range. A /13 has 500k
    /// addresses; probing all of them is a port scan, not a scan for an edge.</summary>
    public int AddressesPerRange { get; set; } = 6;

    /// <summary>Take a small throughput sample. Off makes a scan much lighter.</summary>
    public bool MeasureSpeed { get; set; } = true;

    /// <summary>Stable seed so builds and tests enumerate the same addresses.</summary>
    public int Seed { get; set; } = 0x524958;

    /// <summary>Include the bundled 200-IP Cloudflare source in the candidate
    /// set. The app always wants this; tests that assert exact counts over a
    /// scripted range turn it off.</summary>
    public bool UseBundledSource { get; set; } = true;

    /// <summary>Extra addresses to include, for example a known-good seed.</summary>
    public IReadOnlyList<string> Seeds { get; set; } = new[]
    {
        "104.16.0.1", "104.17.0.1", "104.18.0.1", "162.159.192.1",
        "172.64.0.1", "188.114.96.1",
    };
}

public sealed record ProbeResult(bool Ok, double LatencyMs, double BytesPerSecond, long Bytes)
{
    public static readonly ProbeResult Failed = new(false, 0, 0, 0);
}

public interface IIpProbe
{
    Task<ProbeResult> ProbeAsync(string address, int port, IpScanOptions options, CancellationToken token);
}

public sealed record ScanCandidate
{
    public string Address { get; init; } = "";
    public int Port { get; init; }
    public double LatencyMs { get; init; }
    public double JitterMs { get; init; }
    public double SuccessRate { get; init; }
    public double SpeedBps { get; init; }
    public int Successes { get; init; }
    public int Attempts { get; init; }
    public double Score { get; init; }
    public DateTime At { get; init; } = DateTime.UtcNow;

    public string Endpoint => Address.Contains(':') ? $"[{Address}]:{Port}" : $"{Address}:{Port}";

    public override string ToString() =>
        $"{Endpoint}  {LatencyMs:0}ms  {SuccessRate:P0}  jitter {JitterMs:0}ms  score {Score:0.000}";
}

public sealed record ScanProgress
{
    public int Done { get; init; }
    public int Total { get; init; }
    public int Working { get; init; }
    public string Current { get; init; } = "";
    public ScanCandidate Best { get; init; }
    public bool Running { get; init; }

    public double Fraction => Total <= 0 ? 0 : Math.Clamp((double)Done / Total, 0, 1);
}

/// <summary>
/// The ranking rule, extracted as a pure function so it can be tested without
/// a network. Latency and jitter are normalised against ceilings rather than
/// the current sample set, so a scan of only bad addresses still scores them
/// badly instead of promoting one of them to 1.0.
/// </summary>
public static class IpScoring
{
    public const double LatencyCeilingMs = 800;
    public const double JitterCeilingMs = 400;
    public const double SpeedCeilingBps = 4 * 1024 * 1024;

    public static double Score(double successRate, double latencyMs, double jitterMs, double speedBps)
    {
        var success = Math.Clamp(successRate, 0, 1);
        var latency = 1 - Math.Clamp(latencyMs / LatencyCeilingMs, 0, 1);
        var jitter = 1 - Math.Clamp(jitterMs / JitterCeilingMs, 0, 1);
        var speed = Math.Clamp(speedBps / SpeedCeilingBps, 0, 1);

        // Success dominates: a fast address that answers half the time is worse
        // than a slower one that always answers.
        return Math.Round(0.55 * success + 0.30 * latency + 0.10 * jitter + 0.05 * speed, 6);
    }
}

/// <summary>Cloudflare's published ranges, cached on disk.</summary>
public sealed class CloudflareRanges
{
    public DateTime FetchedAt { get; set; } = DateTime.MinValue;
    public List<string> V4 { get; set; } = new();
    public List<string> V6 { get; set; } = new();

    /// <summary>Used when the network and the cache are both unavailable. These
    /// are Cloudflare's long-standing ranges, not a guess at new ones.</summary>
    public static CloudflareRanges BuiltIn() => new()
    {
        FetchedAt = DateTime.MinValue,
        V4 = new List<string>
        {
            "173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22",
            "103.31.4.0/22", "141.101.64.0/18", "108.162.192.0/18",
            "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22",
            "198.41.128.0/17", "162.158.0.0/15", "104.16.0.0/13",
            "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
        },
        V6 = new List<string>
        {
            "2400:cb00::/32", "2606:4700::/32", "2803:f800::/32",
            "2405:b500::/32", "2405:8100::/32", "2a06:98c0::/29",
            "2c0f:f248::/32",
        },
    };

    public static string CachePath => Path.Combine(AppState.Dir, "cloudflare-ranges.json");

    public static readonly TimeSpan Ttl = TimeSpan.FromDays(7);

    /// <summary>
    /// Loads the ranges: fresh cache, else the published lists, else a stale
    /// cache, else the built-in copy. Always returns something usable.
    /// </summary>
    public static async Task<(CloudflareRanges Ranges, string Source)> LoadAsync(
        HttpClient http, CancellationToken token)
    {
        var cached = ReadCache();
        if (cached != null && DateTime.UtcNow - cached.FetchedAt < Ttl)
            return (cached, "cache");

        if (http != null)
        {
            try
            {
                var v4 = await FetchListAsync(http, "https://www.cloudflare.com/ips-v4", token);
                var v6 = await FetchListAsync(http, "https://www.cloudflare.com/ips-v6", token);
                if (v4.Count > 0)
                {
                    var fresh = new CloudflareRanges
                    {
                        FetchedAt = DateTime.UtcNow,
                        V4 = v4,
                        V6 = v6,
                    };
                    WriteCache(fresh);
                    return (fresh, "network");
                }
            }
            catch
            {
                // Fall through to the cache; this is exactly what the cache is
                // for, and a scan with week-old ranges is still useful.
            }
        }

        if (cached != null) return (cached, "stale-cache");
        return (BuiltIn(), "built-in");
    }

    private static async Task<List<string>> FetchListAsync(HttpClient http, string url, CancellationToken token)
    {
        var text = await http.GetStringAsync(url, token);
        return text.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Where(line => line.Contains('/') && !line.StartsWith('#'))
            .ToList();
    }

    public static CloudflareRanges ReadCache()
    {
        try
        {
            if (!File.Exists(CachePath)) return null;
            return JsonSerializer.Deserialize<CloudflareRanges>(File.ReadAllText(CachePath));
        }
        catch
        {
            return null;
        }
    }

    public static void WriteCache(CloudflareRanges ranges)
    {
        try
        {
            Directory.CreateDirectory(AppState.Dir);
            File.WriteAllText(CachePath, JsonSerializer.Serialize(ranges,
                new JsonSerializerOptions { WriteIndented = true }));
        }
        catch
        {
            // A read-only data folder must not break scanning.
        }
    }

    /// <summary>
    /// Addresses to probe. Deterministic for a given seed, so a rescan tests
    /// the addresses the last one did rather than a fresh lottery.
    /// </summary>
    public IEnumerable<string> CandidateAddresses(IpScanOptions options)
    {
        var seen = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        var lists = new List<List<string>> { V4 };
        if (options.IncludeIpv6) lists.Add(V6);

        foreach (var list in lists)
        {
            foreach (var range in list)
            {
                if (!TryParseCidr(range, out var network, out var prefix)) continue;
                var bits = network.AddressFamily == AddressFamily.InterNetwork ? 32 : 128;
                var count = BigInteger.One << (bits - prefix);
                var take = Math.Max(1, options.AddressesPerRange);
                var stride = BigInteger.Max(BigInteger.One, count / take);
                var seed = BigInteger.Abs(new BigInteger(options.Seed));

                for (var i = 0; i < take; i++)
                {
                    // A stable offset per index keeps the sample spread across
                    // the range instead of always testing the network address.
                    var offset = (new BigInteger(i) * stride + seed) % count;
                    var address = Add(network, offset);
                    if (address != null && seen.Add(address)) yield return address;
                }
            }
        }

        foreach (var seed in options.Seeds ?? Array.Empty<string>())
        {
            if (IPAddress.TryParse(seed, out _) && seen.Add(seed)) yield return seed;
        }
    }

    private static bool TryParseCidr(string cidr, out IPAddress network, out int prefix)
    {
        network = null;
        prefix = 0;
        if (string.IsNullOrWhiteSpace(cidr)) return false;
        var parts = cidr.Split('/');
        if (parts.Length != 2) return false;
        if (!IPAddress.TryParse(parts[0], out network)) return false;
        if (!int.TryParse(parts[1], out prefix)) return false;
        var max = network.AddressFamily == AddressFamily.InterNetwork ? 32 : 128;
        return prefix >= 0 && prefix <= max;
    }

    private static string Add(IPAddress network, BigInteger offset)
    {
        var bytes = network.GetAddressBytes();
        var add = offset.ToByteArray(isUnsigned: true, isBigEndian: true);
        var carry = 0;
        for (int i = bytes.Length - 1, j = add.Length - 1; i >= 0; i--, j--)
        {
            var sum = bytes[i] + (j >= 0 ? add[j] : 0) + carry;
            bytes[i] = (byte)(sum & 0xff);
            carry = sum >> 8;
        }
        return new IPAddress(bytes).ToString();
    }
}

/// <summary>
/// The real probe. Any certificate is accepted: the question here is "does this
/// address complete a TLS handshake and serve bytes", not "is this host who it
/// says it is". Identity is checked by the tunnel's own TLS to Cloudflare.
/// </summary>
public sealed class TlsIpProbe : IIpProbe
{
    private static readonly HttpClient Speed = new(new SocketsHttpHandler
    {
        ConnectTimeout = TimeSpan.FromSeconds(4),
        PooledConnectionLifetime = TimeSpan.FromMinutes(2),
    })
    {
        Timeout = Timeout.InfiniteTimeSpan,
    };

    public async Task<ProbeResult> ProbeAsync(string address, int port, IpScanOptions options, CancellationToken token)
    {
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(token);
        timeout.CancelAfter(Math.Max(250, options.TimeoutMs));
        var clock = Stopwatch.StartNew();

        try
        {
            using var client = new TcpClient(address.Contains(':') ? AddressFamily.InterNetworkV6 : AddressFamily.InterNetwork);
            await client.ConnectAsync(address, port, timeout.Token);

            await using var ssl = new SslStream(client.GetStream(), false,
                (_, _, _, _) => true);
            await ssl.AuthenticateAsClientAsync(new SslClientAuthenticationOptions
            {
                // speed.cloudflare.com is served by the same edge and has a
                // small download endpoint, so one handshake gives both the TLS
                // proof and something to time.
                TargetHost = "speed.cloudflare.com",
                EnabledSslProtocols = System.Security.Authentication.SslProtocols.Tls12 |
                                      System.Security.Authentication.SslProtocols.Tls13,
            }, timeout.Token);

            var latency = clock.Elapsed.TotalMilliseconds;

            if (!options.MeasureSpeed)
                return new ProbeResult(true, latency, 0, 0);

            var request =
                "GET /__down?bytes=4096 HTTP/1.1\r\n" +
                "Host: speed.cloudflare.com\r\n" +
                "User-Agent: RIX-Scanner/1.0\r\n" +
                "Accept: */*\r\n" +
                "Connection: close\r\n\r\n";
            var requestBytes = Encoding.ASCII.GetBytes(request);
            await ssl.WriteAsync(requestBytes, timeout.Token);
            await ssl.FlushAsync(timeout.Token);

            var buffer = new byte[4096];
            var readClock = Stopwatch.StartNew();
            var total = 0;
            while (total < buffer.Length)
            {
                var read = await ssl.ReadAsync(buffer.AsMemory(total), timeout.Token);
                if (read <= 0) break;
                total += read;
                // Stop at the end of the body rather than waiting for the close.
                if (total >= 512 && readClock.ElapsedMilliseconds > 1) break;
            }

            var elapsed = Math.Max(0.001, readClock.Elapsed.TotalSeconds);
            // The first chunk includes headers; treat it as a throughput hint,
            // never as a measurement of the user's line rate.
            var bytes = Math.Max(0, total);
            var bps = bytes / elapsed;
            return new ProbeResult(true, latency, bps, bytes);
        }
        catch (Exception)
        {
            return ProbeResult.Failed;
        }
    }
}

/// <summary>
/// Runs a scan over the candidate set and produces a ranked result.
/// </summary>
public sealed class IpScanner
{
    private readonly IIpProbe _probe;
    private readonly HttpClient _http;

    public IpScanner(IIpProbe probe = null, HttpClient http = null)
    {
        _probe = probe ?? new TlsIpProbe();
        _http = http;
    }

    public async Task<List<ScanCandidate>> ScanAsync(
        IpScanOptions options,
        CloudflareRanges ranges,
        IProgress<ScanProgress> progress,
        CancellationToken token)
    {
        // The bundled 200-address source is the primary candidate set: it is
        // real Cloudflare address space carried in the app, so the scan is a
        // genuine direct TCP+TLS probe of real addresses even when the list
        // endpoint is unreachable. Published ranges add extra samples when
        // available, but are never required for the scan to run.
        var addresses = (options.UseBundledSource ? CloudflareFallbackIps.Pool : Array.Empty<string>())
            .Concat(ranges.CandidateAddresses(options))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        var results = new ConcurrentBag<ScanCandidate>();
        var done = 0;
        var working = 0;
        ScanCandidate best = null;

        var parallel = new ParallelOptions
        {
            MaxDegreeOfParallelism = Math.Clamp(options.Concurrency, 1, 128),
            CancellationToken = token,
        };

        await Parallel.ForEachAsync(addresses, parallel, async (address, ct) =>
        {
            Interlocked.Increment(ref working);
            try
            {
                var candidate = await ProbeAddressAsync(address, options, ct);
                if (candidate != null)
                {
                    results.Add(candidate);
                    lock (results)
                    {
                        if (best == null || candidate.Score > best.Score) best = candidate;
                    }
                }
            }
            finally
            {
                Interlocked.Decrement(ref working);
                var current = Interlocked.Increment(ref done);
                progress?.Report(new ScanProgress
                {
                    Done = current,
                    Total = addresses.Count,
                    Working = Math.Max(0, working),
                    Current = address,
                    Best = best,
                    Running = true,
                });
            }
        });

        return results
            .OrderByDescending(c => c.Score)
            .ThenBy(c => c.LatencyMs)
            .ToList();
    }

    /// <summary>
    /// Tries the ports in order until one completes, then samples it. The port
    /// that answered is part of the result: a blocked 443 is not a blocked IP.
    /// </summary>
    private async Task<ScanCandidate> ProbeAddressAsync(string address, IpScanOptions options, CancellationToken token)
    {
        var samples = new List<(double Latency, double Bps)>();
        var attempts = 0;
        var successes = 0;
        var port = 0;

        foreach (var candidatePort in options.Ports.Distinct())
        {
            var ok = 0;
            var local = new List<(double Latency, double Bps)>();

            for (var i = 0; i < Math.Max(1, options.Samples); i++)
            {
                attempts++;
                var result = await _probe.ProbeAsync(address, candidatePort, options, token);
                if (!result.Ok) continue;
                ok++;
                successes++;
                local.Add((result.LatencyMs, result.BytesPerSecond));
            }

            if (ok > 0)
            {
                port = candidatePort;
                samples = local;
                break;
            }
        }

        if (port == 0 || samples.Count == 0) return null;

        var latency = Median(samples.Select(s => s.Latency).ToList());
        var jitter = Jitter(samples.Select(s => s.Latency).ToList());
        var speed = Median(samples.Select(s => s.Bps).ToList());
        var successRate = (double)successes / Math.Max(1, attempts);

        return new ScanCandidate
        {
            Address = address,
            Port = port,
            LatencyMs = latency,
            JitterMs = jitter,
            SuccessRate = successRate,
            SpeedBps = speed,
            Successes = successes,
            Attempts = attempts,
            Score = IpScoring.Score(successRate, latency, jitter, speed),
            At = DateTime.UtcNow,
        };
    }

    private static double Median(List<double> values)
    {
        if (values.Count == 0) return 0;
        values.Sort();
        var middle = values.Count / 2;
        return values.Count % 2 == 1
            ? values[middle]
            : (values[middle - 1] + values[middle]) / 2;
    }

    private static double Jitter(List<double> values)
    {
        if (values.Count < 2) return 0;
        var mean = values.Average();
        return values.Select(v => Math.Abs(v - mean)).Average();
    }
}

/// <summary>What the scanner remembers between runs.</summary>
public sealed class IpScanCache
{
    public DateTime ScannedAt { get; set; }
    public string Source { get; set; } = "";
    public List<ScanCandidate> Ranked { get; set; } = new();

    /// <summary>The peer the previous session used, persisted so the next
    /// launch continues the rotation rather than reopening on the same
    /// top-ranked address every time.</summary>
    public string LastPeer { get; set; } = "";

    [System.Text.Json.Serialization.JsonIgnore]
    public ScanCandidate Best => Ranked.FirstOrDefault();

    public static string PathOnDisk => Path.Combine(AppState.Dir, "ip-scan.json");

    public static IpScanCache Load()
    {
        try
        {
            if (File.Exists(PathOnDisk))
                return JsonSerializer.Deserialize<IpScanCache>(File.ReadAllText(PathOnDisk)) ?? new IpScanCache();
        }
        catch
        {
        }
        return new IpScanCache();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(AppState.Dir);
            File.WriteAllText(PathOnDisk, JsonSerializer.Serialize(this,
                new JsonSerializerOptions { WriteIndented = true }));
        }
        catch
        {
        }
    }
}

/// <summary>
/// The long-lived scanner: one scan on demand, then periodically in the
/// background, plus the "best address changed" signal the supervisor uses to
/// migrate without waiting for the user.
/// </summary>
public sealed class IpScannerService : IDisposable
{
    private readonly IpScanner _scanner;
    private readonly HttpClient _http;
    private readonly object _gate = new();
    private CancellationTokenSource _loop;
    private bool _disposed;

    public IpScannerService(IpScanner scanner = null, HttpClient http = null)
    {
        _http = http ?? new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
        _scanner = scanner ?? new IpScanner(null, _http);
    }

    public event Action<ScanProgress> Progress;
    public event Action<IpScanCache> Completed;
    public event Action<ScanCandidate> BestChanged;
    public event Action<string> Log;

    public IpScanCache Cache { get; private set; } = IpScanCache.Load();

    public ScanCandidate Best => Cache.Best;

    public bool IsScanning { get; private set; }

    public int IntervalMinutes { get; set; } = 30;

    public IpScanOptions Options { get; set; } = new();

    /// <summary>Runs one scan now. Concurrent calls join the one in flight.</summary>
    public async Task<IpScanCache> ScanNowAsync(CancellationToken token)
    {
        if (IsScanning) return Cache;
        IsScanning = true;
        try
        {
            Log?.Invoke("scan: شروع / starting");

            var (ranges, source) = await CloudflareRanges.LoadAsync(_http, token);
            Log?.Invoke($"scan: {ranges.V4.Count + ranges.V6.Count} ranges from {source}");

            var lastReport = 0;
            var progress = new Progress<ScanProgress>(p =>
            {
                // The UI is a WebView; one message every few candidates is
                // enough to move a bar and does not flood the bridge.
                if (p.Done - lastReport >= 3 || p.Done == p.Total)
                {
                    lastReport = p.Done;
                    Progress?.Invoke(p);
                }
            });

            var ranked = await _scanner.ScanAsync(Options, ranges, progress, token);

            var fresh = new IpScanCache
            {
                ScannedAt = DateTime.UtcNow,
                Source = source,
                Ranked = ranked,
            };

            var previousBest = Cache.Best;
            Cache = fresh;
            fresh.Save();

            Progress?.Invoke(new ScanProgress
            {
                Done = ranked.Count,
                Total = ranked.Count,
                Best = fresh.Best,
                Running = false,
            });
            Completed?.Invoke(fresh);

            if (fresh.Best != null && (previousBest == null ||
                previousBest.Endpoint != fresh.Best.Endpoint))
            {
                BestChanged?.Invoke(fresh.Best);
            }

            Log?.Invoke($"scan: {ranked.Count} usable, best {fresh.Best?.Endpoint ?? "none"}");
            return fresh;
        }
        catch (OperationCanceledException)
        {
            Log?.Invoke("scan: لغو شد / cancelled");
            return Cache;
        }
        catch (Exception ex)
        {
            Log?.Invoke("scan: خطا / failed — " + ex.Message);
            return Cache;
        }
        finally
        {
            IsScanning = false;
            Progress?.Invoke(new ScanProgress { Running = false, Best = Cache.Best });
        }
    }

    /// <summary>Starts the periodic loop. Safe to call more than once.</summary>
    public void Start()
    {
        lock (_gate)
        {
            if (_loop != null) return;
            _loop = new CancellationTokenSource();
            _ = LoopAsync(_loop.Token);
        }
    }

    public void Stop()
    {
        lock (_gate)
        {
            _loop?.Cancel();
            _loop?.Dispose();
            _loop = null;
        }
    }

    private async Task LoopAsync(CancellationToken token)
    {
        // First scan shortly after launch, when the user is still reading the
        // window, rather than competing with the first connect.
        try { await Task.Delay(TimeSpan.FromSeconds(8), token); } catch { return; }

        while (!token.IsCancellationRequested)
        {
            await ScanNowAsync(token);
            try
            {
                await Task.Delay(TimeSpan.FromMinutes(Math.Clamp(IntervalMinutes, 5, 1440)), token);
            }
            catch (OperationCanceledException)
            {
                return;
            }
        }
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        Stop();
        _http.Dispose();
    }
}
