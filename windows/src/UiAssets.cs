using System.Reflection;

namespace Rix.Windows;

/// <summary>
/// The screens, served to the WebView from the assembly itself.
///
/// A folder on disk is tried first, so the interface can be edited without a
/// rebuild while working on it; the embedded copy is what ships, and it cannot
/// go missing.
/// </summary>
internal static class UiAssets
{
    private const string Prefix = "ui/";

    private static readonly Dictionary<string, string> Types = new(StringComparer.OrdinalIgnoreCase)
    {
        [".html"] = "text/html; charset=utf-8",
        [".css"] = "text/css; charset=utf-8",
        [".js"] = "text/javascript; charset=utf-8",
        [".svg"] = "image/svg+xml",
        [".png"] = "image/png",
        [".woff2"] = "font/woff2",
        [".json"] = "application/json; charset=utf-8",
    };

    public static string ContentType(string path)
    {
        var extension = Path.GetExtension(path);
        return Types.TryGetValue(extension, out var type) ? type : "application/octet-stream";
    }

    /// <summary>
    /// Opens <paramref name="path"/> -- "index.html" or "/app.css" -- and returns
    /// the bytes with the type to serve them as, or null when there is no such
    /// file. Anything that tries to climb out of the folder is refused.
    /// </summary>
    public static (Stream Content, string Type)? Open(string path)
    {
        var name = (path ?? "").TrimStart('/');
        if (name.Length == 0) name = "index.html";

        // No traversal: the names come from a page, and a page can be talked
        // into asking for anything.
        if (name.Contains("..", StringComparison.Ordinal) ||
            name.Contains('\\') ||
            name.Contains(':') ||
            name.Contains('\0'))
        {
            return null;
        }

        var onDisk = Path.Combine(AppContext.BaseDirectory, "ui", name.Replace('/', Path.DirectorySeparatorChar));
        try
        {
            if (File.Exists(onDisk))
            {
                return (File.OpenRead(onDisk), ContentType(name));
            }
        }
        catch
        {
            // Fall through to the embedded copy.
        }

        var stream = typeof(UiAssets).Assembly
            .GetManifestResourceStream(Prefix + name);
        if (stream == null) return null;

        return (stream, ContentType(name));
    }

    /// <summary>True when the assembly actually carries the interface.</summary>
    public static bool HasEmbeddedUi() =>
        typeof(UiAssets).Assembly
            .GetManifestResourceNames()
            .Any(n => n.EndsWith("index.html", StringComparison.OrdinalIgnoreCase));
}
