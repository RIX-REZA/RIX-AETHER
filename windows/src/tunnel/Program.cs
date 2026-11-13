using System.Text;
using Rix.Windows.Core;

namespace Rix.Windows.Tunnel;

/// <summary>
/// The elevated half of Full Tunnel.
///
/// The main RIX executable is asInvoker. When the user selects Full Tunnel,
/// the UI starts this file with <c>runas</c>; Windows shows the UAC prompt, and
/// only this process touches routes, the adapter and the firewall. The UI
/// stays open and usable, and declining UAC changes nothing.
///
/// Lifecycle is a stop file rather than a signal: the launcher writes it on
/// disconnect, and this process polls for it. A stop file survives a UI crash
/// in the sense that the next launch can still request cleanup; the routes are
/// also removed by <c>Stop</c> on any normal exit.
/// </summary>
internal static class Program
{
    private static int Main(string[] args)
    {
        var options = Parse(args);
        if (options == null)
        {
            Console.Error.WriteLine("usage: RIX-Tunnel --data <dir> --socks <port> [--adapter RIX] " +
                                    "[--mtu 1400] [--kill] [--split a,b] [--stop-file path]");
            return 2;
        }

        var status = Path.Combine(options.DataDir, "tunnel.state");
        try
        {
            Directory.CreateDirectory(options.DataDir);
            // A state file left by a killed helper is the only record that the
            // machine may still have RIX routes. It is cleaned up on our own
            // Stop below; a stale one is reported to the next elevated run.
            File.WriteAllText(status, "starting " + DateTime.UtcNow.ToString("u"));
        }
        catch
        {
        }

        using var tunnel = new FullTunnel(options.Tunnel, options.DataDir, Log);
        var ready = tunnel.StartAsync(CancellationToken.None).GetAwaiter().GetResult();
        if (!ready.Ok)
        {
            Console.Error.WriteLine("tunnel: " + ready.Error);
            SafeDelete(status);
            return 1;
        }

        Console.WriteLine("READY");
        try { File.WriteAllText(status, "ready " + DateTime.UtcNow.ToString("u")); } catch { }

        var stopFile = options.StopFile;
        var cancelled = false;
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            cancelled = true;
        };

        while (!cancelled)
        {
            if (!string.IsNullOrEmpty(stopFile) && File.Exists(stopFile))
                break;
            Thread.Sleep(400);
        }

        Log("tunnel: stopping");
        tunnel.Stop();
        SafeDelete(status);
        if (!string.IsNullOrEmpty(stopFile))
        {
            try { File.Delete(stopFile); } catch { }
        }
        return 0;
    }

    private static void Log(string line)
    {
        try { Console.WriteLine(line); } catch { }
    }

    private sealed class Parsed
    {
        public string DataDir;
        public string StopFile;
        public FullTunnelOptions Tunnel = new();
    }

    private static Parsed Parse(string[] args)
    {
        var parsed = new Parsed();
        for (var i = 0; i < args.Length; i++)
        {
            string Next() => i + 1 < args.Length ? args[++i] : null;
            var value = "";
            switch (args[i])
            {
                case "--data":
                    parsed.DataDir = value = Next();
                    break;
                case "--stop-file":
                    parsed.StopFile = Next();
                    break;
                case "--socks":
                    value = Next();
                    if (int.TryParse(value, out var socks)) parsed.Tunnel.SocksPort = socks;
                    break;
                case "--adapter":
                    value = Next();
                    if (!string.IsNullOrWhiteSpace(value)) parsed.Tunnel.AdapterName = value;
                    break;
                case "--mtu":
                    value = Next();
                    if (int.TryParse(value, out var mtu)) parsed.Tunnel.Mtu = mtu;
                    break;
                case "--kill":
                    parsed.Tunnel.KillSwitch = true;
                    break;
                case "--split":
                    value = Next();
                    parsed.Tunnel.SplitTunnelAddresses = (value ?? "")
                        .Split(new[] { ',', ';' }, StringSplitOptions.RemoveEmptyEntries)
                        .Select(s => s.Trim())
                        .Where(s => s.Length > 0)
                        .ToList();
                    break;
            }
        }

        return string.IsNullOrWhiteSpace(parsed.DataDir) || parsed.Tunnel.SocksPort <= 0
            ? null
            : parsed;
    }

    private static void SafeDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }
}
