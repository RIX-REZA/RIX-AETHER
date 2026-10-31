using System.Globalization;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Text;

namespace Rix.Windows.Core;

/// <summary>What the far end says about the connection it just carried.</summary>
/// <param name="Ip">The address that reached Cloudflare -- the exit address.</param>
/// <param name="Country">Its two-letter country, from Cloudflare's own view.</param>
/// <param name="Colo">The Cloudflare datacentre that answered.</param>
/// <param name="Warp">Whether Cloudflare saw the request arrive over WARP.</param>
public sealed record Egress(string Ip, string Country, string Colo, string Warp)
{
    public override string ToString()
    {
        var parts = new List<string>();
        if (Country.Length > 0) parts.Add(Country);
        if (Colo.Length > 0) parts.Add("colo " + Colo);
        if (Warp.Length > 0) parts.Add("warp " + Warp);
        return parts.Count == 0 ? Ip : Ip + "  (" + string.Join(", ", parts) + ")";
    }
}

/// <summary>
/// Proves the tunnel, and names the address it leaves from.
///
/// A listener that accepts a connection says nothing about whether anything
/// comes back through it, so the app asks a real question of a real server every
/// time: a SOCKS5 CONNECT, then Cloudflare's <c>/cdn-cgi/trace</c>, which answers
/// with the address the request arrived from and the country Cloudflare places
/// it in. That is the same endpoint and the same code path as on the phone, so
/// both halves of the product agree on what "connected" means.
///
/// The request is HTTPS. A plaintext probe can be answered by any middlebox on
/// the path -- a captive portal, a transparent proxy, the hotel network's own
/// injection -- and every one of those answers would have looked like proof that
/// the tunnel works. TLS with the platform's default certificate and hostname
/// verification means the answer came from a host that proved it is Cloudflare,
/// so a successful probe says something about the path rather than only about
/// the bytes.
/// </summary>
public static class Probe
{
    /// <summary>
    /// The same endpoint under three names.
    ///
    /// The name is answered by whichever edge is nearest, and on a network where
    /// DNS for it is poisoned or the name is filtered the probe would fail even
    /// though the tunnel works; the literal is tried last and still has to pass
    /// a certificate check that names 1.1.1.1.
    /// </summary>
    private static readonly string[] Targets = { "cloudflare.com", "www.cloudflare.com", "1.1.1.1" };

    private const int HttpsPort = 443;
    private const int MaxHead = 8 * 1024;
    private const int MaxBody = 16 * 1024;

    private static readonly byte[] HeaderEnd = { (byte)'\r', (byte)'\n', (byte)'\r', (byte)'\n' };
    private static readonly byte[] CrLf = { (byte)'\r', (byte)'\n' };

    /// <summary>
    /// Fetches the trace through the SOCKS5 listener on <paramref name="port"/>.
    /// Returns null when nothing usable came back, which is the honest answer
    /// while a tunnel is still being built.
    /// </summary>
    public static async Task<Egress> ThroughSocksAsync(int port, int timeoutMs, CancellationToken token)
    {
        // One budget for all three names, like the phone: three full timeouts
        // stacked would make a blocked network look like a hung connect.
        var deadline = Environment.TickCount64 + timeoutMs;
        foreach (var host in Targets)
        {
            var remaining = deadline - Environment.TickCount64;
            if (remaining < 250) break;

            try
            {
                using var client = new TcpClient();
                using var attempt = CancellationTokenSource.CreateLinkedTokenSource(token);
                attempt.CancelAfter(TimeSpan.FromMilliseconds(remaining));

                await client.ConnectAsync(IPAddress.Loopback, port, attempt.Token);
                await using var stream = client.GetStream();

                await NegotiateSocks5Async(stream, host, HttpsPort, attempt.Token);
                var body = await RequestTraceAsync(stream, host, attempt.Token);
                if (body == null) continue;

                var egress = Parse(body);
                if (egress != null) return egress;
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                throw;
            }
            catch
            {
                // Try the next target; the caller only needs to know whether any
                // of them answered.
            }
        }
        return null;
    }

    /// <summary>
    /// The same question asked without a tunnel, for the "your connection" line
    /// beside the exit address. Null just means the direct route is blocked,
    /// which is itself worth showing.
    /// </summary>
    public static async Task<Egress> DirectAsync(int timeoutMs, CancellationToken token)
    {
        var deadline = Environment.TickCount64 + timeoutMs;
        foreach (var host in Targets)
        {
            var remaining = deadline - Environment.TickCount64;
            if (remaining < 250) break;

            try
            {
                using var client = new TcpClient();
                using var attempt = CancellationTokenSource.CreateLinkedTokenSource(token);
                attempt.CancelAfter(TimeSpan.FromMilliseconds(remaining));

                await client.ConnectAsync(host, HttpsPort, attempt.Token);
                await using var stream = client.GetStream();

                var body = await RequestTraceAsync(stream, host, attempt.Token);
                if (body == null) continue;

                var egress = Parse(body);
                if (egress != null) return egress;
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                throw;
            }
            catch
            {
            }
        }
        return null;
    }

    // ------------------------------------------------------------------- socks

    /// <summary>
    /// The SOCKS5 greeting and a CONNECT. The name is sent as a name, so it is
    /// resolved at the far end: a lookup here would go to the local resolver and
    /// leak the destination, and on a filtered network it is the lookup that
    /// fails first.
    /// </summary>
    private static async Task NegotiateSocks5Async(Stream stream, string host, int port, CancellationToken token)
    {
        await stream.WriteAsync(new byte[] { 0x05, 0x01, 0x00 }, token);
        await stream.FlushAsync(token);

        var greeting = new byte[2];
        await ReadExactlyAsync(stream, greeting, token);
        if (greeting[0] != 0x05) throw new IOException("not a SOCKS5 proxy");
        if (greeting[1] != 0x00) throw new IOException("the proxy wants authentication");

        var name = Encoding.ASCII.GetBytes(host);
        var request = new byte[5 + name.Length + 2];
        request[0] = 0x05;                                  // version
        request[1] = 0x01;                                  // CONNECT
        request[2] = 0x00;                                  // reserved
        request[3] = 0x03;                                  // address is a name
        request[4] = (byte)name.Length;
        name.CopyTo(request, 5);
        request[^2] = (byte)(port >> 8);
        request[^1] = (byte)(port & 0xff);

        await stream.WriteAsync(request, token);
        await stream.FlushAsync(token);

        var reply = new byte[4];
        await ReadExactlyAsync(stream, reply, token);
        if (reply[0] != 0x05) throw new IOException("SOCKS5 replied with version " + reply[0]);
        if (reply[1] != 0x00) throw new IOException("SOCKS5 refused with code " + reply[1]);

        // The bound address has to be consumed before the payload, whatever
        // shape the proxy chose to report it in.
        switch (reply[3])
        {
            case 0x01:
                await ReadExactlyAsync(stream, new byte[4 + 2], token);
                break;
            case 0x03:
                var length = new byte[1];
                await ReadExactlyAsync(stream, length, token);
                await ReadExactlyAsync(stream, new byte[length[0] + 2], token);
                break;
            case 0x04:
                await ReadExactlyAsync(stream, new byte[16 + 2], token);
                break;
            default:
                throw new IOException("SOCKS5 replied with address type " + reply[3]);
        }
    }

    /// <summary>
    /// Wraps the (possibly proxied) stream in TLS, asks for the trace, and
    /// returns a complete, successful body.
    ///
    /// Certificate and hostname validation are left at their defaults: no
    /// callback is installed and no policy is relaxed, so a name that does not
    /// match the certificate -- or a chain the machine does not trust -- fails
    /// the handshake instead of being reported as a working tunnel.
    /// </summary>
    private static async Task<string> RequestTraceAsync(Stream inner, string host, CancellationToken token)
    {
        await using var ssl = new SslStream(inner, leaveInnerStreamOpen: false);
        await ssl.AuthenticateAsClientAsync(
            new SslClientAuthenticationOptions
            {
                TargetHost = host,
                EnabledSslProtocols = SslProtocols.None,
            },
            token);

        var request =
            "GET /cdn-cgi/trace HTTP/1.1\r\n" +
            "Host: " + host + "\r\n" +
            "User-Agent: RIX\r\n" +
            "Accept: text/plain\r\n" +
            "Connection: close\r\n\r\n";
        await ssl.WriteAsync(Encoding.ASCII.GetBytes(request), token);
        await ssl.FlushAsync(token);

        return await ReadResponseAsync(ssl, token);
    }

    /// <summary>
    /// One HTTP/1.x response, returned only when it is a 200 and the framing says
    /// the body is complete.
    ///
    /// Complete means a declared Content-Length that was fully read, the terminal
    /// chunk of a chunked body, or end of stream for a close-delimited one. A
    /// body that stops early is a truncated answer, and a truncated trace parses
    /// into whatever fields happened to arrive -- exactly the fabricated success
    /// this refuses to report.
    /// </summary>
    private static async Task<string> ReadResponseAsync(Stream stream, CancellationToken token)
    {
        var headBytes = new List<byte>();
        byte[] rest;

        while (true)
        {
            var at = IndexOf(headBytes, HeaderEnd);
            if (at >= 0)
            {
                rest = headBytes.GetRange(at + HeaderEnd.Length, headBytes.Count - at - HeaderEnd.Length).ToArray();
                headBytes.RemoveRange(at, headBytes.Count - at);
                break;
            }
            if (headBytes.Count > MaxHead) return null;
            if (await ReadIntoAsync(stream, headBytes, token) == 0) return null;
        }

        var lines = Encoding.Latin1.GetString(headBytes.ToArray()).Split("\r\n");
        if (lines.Length == 0 || !IsOkStatus(lines[0])) return null;

        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var line in lines.Skip(1))
        {
            var colon = line.IndexOf(':');
            if (colon <= 0) continue;
            headers[line[..colon].Trim()] = line[(colon + 1)..].Trim();
        }

        var chunked = headers.TryGetValue("transfer-encoding", out var te) &&
                      te.Contains("chunked", StringComparison.OrdinalIgnoreCase);

        if (chunked) return await ReadChunkedAsync(stream, rest, token);

        if (headers.TryGetValue("content-length", out var lengthText))
        {
            if (!long.TryParse(lengthText, NumberStyles.Integer, CultureInfo.InvariantCulture, out var length)) return null;
            if (length < 0 || length > MaxBody) return null;

            var body = new byte[length];
            var filled = Math.Min(rest.Length, body.Length);
            Array.Copy(rest, body, filled);
            while (filled < body.Length)
            {
                var buffer = new byte[body.Length - filled];
                var read = await stream.ReadAsync(buffer, token);
                if (read <= 0) return null;
                Array.Copy(buffer, 0, body, filled, read);
                filled += read;
            }
            return Encoding.UTF8.GetString(body);
        }

        // Close-delimited: the body is complete only at end of stream. Hitting
        // the size cap before that end is a truncated answer, not a complete one.
        var text = new StringBuilder(Encoding.UTF8.GetString(rest));
        var more = new List<byte>();
        while (true)
        {
            if (text.Length >= MaxBody) return null;
            if (await ReadIntoAsync(stream, more, token) == 0) break;
            text.Append(Encoding.UTF8.GetString(more.ToArray()));
            more.Clear();
        }
        return text.ToString();
    }

    private static async Task<string> ReadChunkedAsync(Stream stream, byte[] initial, CancellationToken token)
    {
        var pending = new List<byte>(initial);
        var output = new StringBuilder();

        while (true)
        {
            var lineEnd = IndexOf(pending, CrLf);
            if (lineEnd < 0)
            {
                if (await ReadIntoAsync(stream, pending, token) == 0) return null;
                continue;
            }

            var sizeText = Encoding.ASCII.GetString(pending.GetRange(0, lineEnd).ToArray())
                .Split(';')[0].Trim();
            if (!int.TryParse(sizeText, NumberStyles.HexNumber, CultureInfo.InvariantCulture, out var size)) return null;
            pending.RemoveRange(0, lineEnd + CrLf.Length);
            if (size == 0) return output.ToString();
            if (output.Length + size > MaxBody) return null;

            while (pending.Count < size + CrLf.Length)
            {
                if (await ReadIntoAsync(stream, pending, token) == 0) return null;
            }
            output.Append(Encoding.UTF8.GetString(pending.GetRange(0, size).ToArray()));
            pending.RemoveRange(0, size + CrLf.Length);
        }
    }

    /// <summary>
    /// Appends whatever arrives next. Returns 0 only at end of stream; real
    /// failures throw, so a broken read is never mistaken for a finished body.
    /// </summary>
    private static async Task<int> ReadIntoAsync(Stream stream, List<byte> into, CancellationToken token)
    {
        var buffer = new byte[2048];
        var read = await stream.ReadAsync(buffer, token);
        if (read <= 0) return 0;
        into.AddRange(read == buffer.Length ? buffer : buffer[..read]);
        return read;
    }

    private static async Task ReadExactlyAsync(Stream stream, byte[] buffer, CancellationToken token)
    {
        var offset = 0;
        while (offset < buffer.Length)
        {
            var read = await stream.ReadAsync(buffer.AsMemory(offset), token);
            if (read <= 0) throw new IOException("the proxy closed the connection");
            offset += read;
        }
    }

    // ------------------------------------------------------------------- parse

    /// <summary>
    /// Exactly <c>HTTP/1.0 200</c> or <c>HTTP/1.1 200</c>, with an optional
    /// reason phrase. A captive portal's 302 or a proxy's 407 is a middlebox
    /// answering, not Cloudflare.
    /// </summary>
    private static bool IsOkStatus(string line)
    {
        line = line.TrimEnd();
        if (!line.StartsWith("HTTP/1.", StringComparison.Ordinal)) return false;
        if (line.Length < 12) return false;
        if (line[7] != '0' && line[7] != '1') return false;
        if (line[8] != ' ') return false;
        if (!line.AsSpan(9, 3).SequenceEqual("200")) return false;
        return line.Length == 12 || line[12] == ' ';
    }

    /// <summary>
    /// `ip=1.2.3.4\nloc=DE\ncolo=FRA\nwarp=on\n...`, one field per line.
    ///
    /// A real trace always carries all four, so any answer that does not is not
    /// a trace: an HTML page with a stray `ip=` in it, or a body that stopped
    /// after the first line, is refused rather than shown as a working tunnel.
    /// </summary>
    private static Egress Parse(string body)
    {
        if (string.IsNullOrWhiteSpace(body)) return null;

        var fields = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var line in body.Split('\n'))
        {
            var trimmed = line.Trim();
            var equals = trimmed.IndexOf('=');
            if (equals <= 0 || equals == trimmed.Length - 1) continue;
            fields[trimmed[..equals]] = trimmed[(equals + 1)..].Trim();
        }

        if (!fields.TryGetValue("ip", out var ip) || !IsIpLiteral(ip)) return null;
        if (!fields.TryGetValue("warp", out var warp)) return null;
        warp = warp.ToLowerInvariant();
        if (warp is not ("on" or "off")) return null;
        if (!fields.TryGetValue("colo", out var colo) || !IsUpperLetters(colo, 3)) return null;
        if (!fields.TryGetValue("loc", out var loc) || !IsUpperLetters(loc, 2)) return null;

        return new Egress(ip, loc, colo, warp);
    }

    private static bool IsIpLiteral(string value) =>
        IsIpv4Literal(value) || IsIpv6Literal(value);

    private static bool IsIpv4Literal(string text)
    {
        var parts = text.Split('.');
        if (parts.Length != 4) return false;
        foreach (var part in parts)
        {
            if (part.Length is < 1 or > 3) return false;
            if (!part.All(char.IsAsciiDigit)) return false;
            if (!int.TryParse(part, NumberStyles.None, CultureInfo.InvariantCulture, out var number)) return false;
            if (number is < 0 or > 255) return false;
        }
        return true;
    }

    private static bool IsIpv6Literal(string text)
    {
        if (!text.Contains(':') || text.Contains('%')) return false;
        return IPAddress.TryParse(text, out var address) &&
               address.AddressFamily == AddressFamily.InterNetworkV6;
    }

    private static bool IsUpperLetters(string text, int length) =>
        text.Length == length && text.All(c => c is >= 'A' and <= 'Z');

    private static int IndexOf(IReadOnlyList<byte> data, byte[] needle, int from = 0)
    {
        for (var i = from; i + needle.Length <= data.Count; i++)
        {
            var match = true;
            for (var j = 0; j < needle.Length; j++)
            {
                if (data[i + j] != needle[j])
                {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }
}