using System.Text.Json;
using System.Text.Json.Serialization;

namespace Rix.Windows;

/// <summary>The core's transport, mapped to the real CLI selectors.</summary>
public static class Transports
{
    public const string MasqueH3 = "MASQUE_H3";
    public const string MasqueH2 = "MASQUE_H2";
    public const string WireGuard = "WIREGUARD";
    public const string Gool = "GOOL";
    public const string GoolClassic = "GOOL_CLASSIC";
    public const string Mim = "MIM";

    public static readonly string[] All =
    {
        MasqueH3, MasqueH2, WireGuard, Gool, GoolClassic, Mim
    };

    public static string[] Args(string transport) => transport switch
    {
        MasqueH2 => new[] { "--masque", "--h2" },
        WireGuard => new[] { "--wg" },
        Gool => new[] { "--gool" },
        GoolClassic => new[] { "--gool-classic" },
        Mim => new[] { "--mim" },
        _ => new[] { "--masque" },
    };

    public static string Label(string transport, bool en) => transport switch
    {
        MasqueH2 => "MASQUE / HTTP-2",
        WireGuard => "WireGuard",
        Gool => en ? "Gool (WG inside MASQUE)" : "Gool (WG داخل MASQUE)",
        GoolClassic => en ? "Gool classic (WG in WG)" : "Gool کلاسیک (WG در WG)",
        Mim => en ? "MASQUE in MASQUE" : "MASQUE در MASQUE",
        _ => "MASQUE / HTTP-3",
    };
}

/// <summary>
/// Browser pages asked the core for these; kept as a small helper so the UI and
/// the argument builder cannot drift apart.
/// </summary>
public static class ScanModes
{
    public static readonly (string Value, string Fa, string En)[] All =
    {
        ("DEFAULT", "پیش‌فرض هسته", "Core default"),
        ("TURBO", "Turbo (سریع‌ترین)", "Turbo (fastest)"),
        ("BALANCED", "Balanced", "Balanced"),
        ("THOROUGH", "Thorough (کامل)", "Thorough"),
        ("VERIFIED", "Verified", "Verified"),
        ("IRONCLAD", "Ironclad (تست واقعی)", "Ironclad (real test)"),
    };

    public static string Cli(string scan) =>
        scan == "DEFAULT" ? null : scan.ToLowerInvariant();
}

/// <summary>The Psiphon shape, as aether names it.</summary>
public static class PsiphonModes
{
    public static readonly (string Value, string Fa, string En)[] All =
    {
        ("auto", "خودکار (انتخاب سایفون)", "Automatic"),
        ("direct", "مستقیم (سریع)", "Direct (faster)"),
        ("cdn", "از پشت CDN", "Behind a CDN"),
    };
}

/// <summary>
/// Everything the screens can change. One flat object, serialised as-is, so a
/// setting added here is persisted and round-tripped without further wiring.
///
/// A record so that a fallback candidate is `options with { Transport = ... }`
/// rather than a hand-written copy that would silently miss a field the next
/// time one is added.
/// </summary>
public sealed record RixOptions
{
    // ---- connection
    public string Transport { get; set; } = Transports.MasqueH3;
    public bool AutoFallback { get; set; } = true;
    public int BindPort { get; set; } = 1819;
    public int HttpPort { get; set; } = 1820;
    public string Dns { get; set; } = "1.1.1.1,1.0.0.1";
    public string IpFamily { get; set; } = "V4";
    public string ExitLoc { get; set; } = "";
    public string Peer { get; set; } = "";
    public string MasqueSni { get; set; } = "";
    public string UpstreamProxy { get; set; } = "";
    public int ReconnectSecs { get; set; } = 2;
    public bool QuickReconnect { get; set; } = true;
    public int Keepalive { get; set; } = 5;
    public bool Fragment { get; set; }
    public string FragmentSize { get; set; } = "";
    public string FragmentDelay { get; set; } = "";
    public string ScanMode { get; set; } = "DEFAULT";
    /// <summary>Empty means "detect": the core sizes its buffers from the CPU
    /// count and the installed memory, which beats any fixed guess here.</summary>
    public string Perf { get; set; } = "";
    public string LogLevel { get; set; } = "INFO";
    public string Ech { get; set; } = "";
    public string RouteDirect { get; set; } = "";
    public string RouteBlock { get; set; } = "";
    public string ExtraArgs { get; set; } = "";

    // ---- psiphon, BETA
    public bool Psiphon { get; set; }
    public string PsiphonRegion { get; set; } = "";
    public string PsiphonMode { get; set; } = "auto";

    // ---- app
    public string Theme { get; set; } = "dark";
    public string Lang { get; set; } = "fa";
    public bool StartWithWindows { get; set; }
    public bool MinimizeToTray { get; set; } = true;

    /// <summary>Where the SOCKS5 endpoint apps can be pointed at lives.</summary>
    [JsonIgnore]
    public int SocksPort => Psiphon ? PsiphonSocksPort : BindPort;

    /// <summary>Where the HTTP CONNECT endpoint the system proxy uses lives.</summary>
    [JsonIgnore]
    public int ProxyPort => Psiphon ? PsiphonHttpPort : HttpPort;

    // Fixed, and away from the two ports above, so the four listeners can never
    // collide whatever the user types in.
    private const int PsiphonSocksPort = 1821;
    private const int PsiphonHttpPort = 1822;

    /// <summary>
    /// The argv handed to aether.exe.
    ///
    /// ExtraArgs comes last on purpose: the core parses left to right into
    /// environment variables, so anything typed there overrides what the
    /// screens set.
    /// </summary>
    public List<string> ToArgs(string configPath)
    {
        var args = new List<string>
        {
            "--bind", $"127.0.0.1:{BindPort}",
            "--config", configPath,
            "--log-level", LogLevel.ToLowerInvariant(),
            // The numbers under the orb come from the core itself; without this
            // aether reports nothing and the UI would have to invent them.
            "--stats",
            "--stats-secs", "1",
        };

        if (!string.IsNullOrWhiteSpace(Perf))
        {
            args.Add("--perf");
            args.Add(Perf.ToLowerInvariant());
        }

        args.AddRange(Transports.Args(Transport));

        if (!string.IsNullOrWhiteSpace(Dns)) { args.Add("--dns"); args.Add(Dns.Trim()); }
        args.Add("--ip"); args.Add(IpFamily.ToLowerInvariant());

        AddIfSet(args, "--exit-loc", ExitLoc);
        AddIfSet(args, "--peer", Peer);
        AddIfSet(args, "--masque-sni", MasqueSni);
        AddIfSet(args, "--upstream", UpstreamProxy);

        args.Add("--reconnect-secs");
        args.Add(Math.Clamp(ReconnectSecs, 0, 600).ToString());
        args.Add(QuickReconnect ? "--quick-reconnect" : "--no-quick-reconnect");
        if (Keepalive > 0) { args.Add("--keepalive"); args.Add(Keepalive.ToString()); }

        if (Fragment)
        {
            args.Add("--fragment");
            AddIfSet(args, "--fragment-size", FragmentSize);
            AddIfSet(args, "--fragment-delay", FragmentDelay);
        }

        // One flag either way: --scan and --ironclad write the same variable, so
        // passing both would just be the last one winning.
        var scan = ScanModes.Cli(ScanMode);
        if (scan != null) { args.Add("--scan"); args.Add(scan); }

        AddIfSet(args, "--ech", Ech);
        AddIfSet(args, "--route-direct", RouteDirect);
        AddIfSet(args, "--route-block", RouteBlock);

        if (Psiphon)
        {
            // The chain the phone builds by hand, which the core does itself
            // here: aether -> warp -> psiphon -> internet, with psiphon as the
            // exit so the country is the one asked for.
            args.Add("--psiphon");
            args.Add("--psiphon-mode");
            args.Add(string.IsNullOrWhiteSpace(PsiphonMode) ? "auto" : PsiphonMode);
            args.Add("--psiphon-bind");
            args.Add($"127.0.0.1:{PsiphonSocksPort}");
            args.Add("--psiphon-http");
            args.Add($"127.0.0.1:{PsiphonHttpPort}");
            // Named outright instead of left to the default, so the uninstaller
            // knows exactly which folder to remove and nothing lands beside the
            // identity file.
            args.Add("--psiphon-dir");
            args.Add(Path.Combine(AppState.Dir, "psiphon"));
            var region = PsiphonRegion.Trim().ToUpperInvariant();
            if (region.Length == 2) { args.Add("--psiphon-region"); args.Add(region); }
        }
        else
        {
            // Only in proxy mode: with psiphon on, the http listener that matters
            // is psiphon's own, and a second one on the core would be dialled
            // *into* the tunnel, i.e. the wrong exit.
            args.Add("--http-proxy");
            args.Add($"127.0.0.1:{HttpPort}");
        }

        args.AddRange(SplitRaw(ExtraArgs));
        return args;
    }

    private static void AddIfSet(List<string> args, string flag, string value)
    {
        if (string.IsNullOrWhiteSpace(value)) return;
        args.Add(flag);
        args.Add(value.Trim());
    }

    /// <summary>Whitespace split that still honours "quoted values".</summary>
    private static IEnumerable<string> SplitRaw(string raw)
    {
        if (string.IsNullOrWhiteSpace(raw)) yield break;

        var token = new System.Text.StringBuilder();
        char? quote = null;
        foreach (var ch in raw)
        {
            if (quote != null && ch == quote) { quote = null; continue; }
            if (quote == null && (ch == '"' || ch == '\'')) { quote = ch; continue; }
            if (quote == null && char.IsWhiteSpace(ch))
            {
                if (token.Length > 0) { yield return token.ToString(); token.Clear(); }
                continue;
            }
            token.Append(ch);
        }
        if (token.Length > 0) yield return token.ToString();
    }
}

/// <summary>
/// The persisted state: the options plus the two things that belong to the app
/// rather than to the tunnel.
/// </summary>
public sealed class AppState
{
    public RixOptions Options { get; set; } = new();

    public static string Dir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "RIX");

    public static string FilePath => Path.Combine(Dir, "rix.settings.json");

    public static string ConfigPath => Path.Combine(Dir, "aether.toml");

    public static string LogPath => Path.Combine(Dir, "aether.log");

    private static readonly JsonSerializerOptions Json = new()
    {
        WriteIndented = true,
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
    };

    public static AppState Load()
    {
        try
        {
            if (File.Exists(FilePath))
            {
                var text = File.ReadAllText(FilePath);
                var loaded = JsonSerializer.Deserialize<AppState>(text, Json);
                if (loaded?.Options != null) return loaded;
            }
        }
        catch
        {
            // A settings file that cannot be read must not stop the app from
            // starting: the defaults are always valid.
        }
        return new AppState();
    }

    public void Save()
    {
        try
        {
            Directory.CreateDirectory(Dir);
            File.WriteAllText(FilePath, JsonSerializer.Serialize(this, Json));
        }
        catch
        {
            // Saving is best effort; a full disk must not crash the UI.
        }
    }
}