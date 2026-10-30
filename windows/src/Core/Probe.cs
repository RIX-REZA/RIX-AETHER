using System.Net.Sockets;
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
/// it in. That is the same endpoint the phone uses, and it is the reason the
/// address shown is the one the far end actually sees rather than a guess.
///
/// The request goes to port 80 on purpose. Over 443 the answer would still be
/// Cloudflare's, but a TLS handshake would make the check depend on the
/// certificate chain the transport is presenting, and what is being measured
/// here is whether traffic flows at all.
/// </summary>
public static class Probe
{
    private static readonly string[] Targets = { "cloudflare.com", "1.1.1.1" };

    /// <summary>
    /// Fetches the trace through the SOCKS5 listener on <paramref name="port"/>.
    /// Returns null when nothing usable came back, which is the honest answer
    /// while a tunnel is still being built.
    /// </summary>
    public static async Task<Egress> ThroughSocksAsync(int port, int timeoutMs, CancellationToken token)
    {
        foreach (var host in Targets)
        {
            try
            {
                using var client = new TcpClient();
                using var attempt = CancellationTokenSource.CreateLinkedTokenSource(token);
                attempt.CancelAfter(timeoutMs);

                await client.ConnectAsync("127.0.0.1", port, attempt.Token);
                using var stream = client.GetStream();
                stream.ReadTimeout = timeoutMs;
                stream.WriteTimeout = timeoutMs;

                await NegotiateSocks5Async(stream, host, 80, attempt.Token);
                await SendTraceRequestAsync(stream, host, attempt.Token);
                var body = await ReadAllAsync(stream, attempt.Token);

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
        foreach (var host in Targets)
        {
            try
            {
                using var client = new TcpClient();
                using var attempt = CancellationTokenSource.CreateLinkedTokenSource(token);
                attempt.CancelAfter(timeoutMs);

                await client.ConnectAsync(host, 80, attempt.Token);
                using var stream = client.GetStream();
                stream.ReadTimeout = timeoutMs;
                stream.WriteTimeout = timeoutMs;

                await SendTraceRequestAsync(stream, host, attempt.Token);
                var body = await ReadAllAsync(stream, attempt.Token);

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

    private static async Task SendTraceRequestAsync(Stream stream, string host, CancellationToken token)
    {
        var request =
            "GET /cdn-cgi/trace HTTP/1.1\r\n" +
            "Host: " + host + "\r\n" +
            "User-Agent: RIX\r\n" +
            "Accept: text/plain\r\n" +
            "Connection: close\r\n\r\n";
        await stream.WriteAsync(Encoding.ASCII.GetBytes(request), token);
        await stream.FlushAsync(token);
    }

    /// <summary>
    /// Reads to the end. `Connection: close` means the server ends the response,
    /// so there is no chunked framing to unpick -- and a short read is fine,
    /// because the fields wanted are all in the first few hundred bytes.
    /// </summary>
    private static async Task<string> ReadAllAsync(Stream stream, CancellationToken token)
    {
        var buffer = new byte[4096];
        var text = new StringBuilder();
        while (text.Length < 16 * 1024)
        {
            var read = await stream.ReadAsync(buffer, token);
            if (read <= 0) break;
            text.Append(Encoding.UTF8.GetString(buffer, 0, read));
        }
        return text.ToString();
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
    /// `ip=1.2.3.4\nloc=DE\ncolo=FRA\nwarp=on\n...`, one field per line.
    /// </summary>
    private static Egress Parse(string response)
    {
        if (string.IsNullOrWhiteSpace(response)) return null;

        var separator = response.IndexOf("\r\n\r\n", StringComparison.Ordinal);
        var body = separator >= 0 ? response[(separator + 4)..] : response;

        string ip = null, country = "", colo = "", warp = "";
        foreach (var line in body.Split('\n'))
        {
            var trimmed = line.Trim();
            var equals = trimmed.IndexOf('=');
            if (equals <= 0) continue;

            var key = trimmed[..equals];
            var value = trimmed[(equals + 1)..].Trim();
            switch (key)
            {
                case "ip": ip = value; break;
                case "loc": country = value; break;
                case "colo": colo = value; break;
                case "warp": warp = value; break;
            }
        }

        // Without an address there is no answer here, only a page.
        return string.IsNullOrEmpty(ip) ? null : new Egress(ip, country, colo, warp);
    }
}