namespace Rix.Windows;

/// <summary>
/// <c>--selfcheck</c>: proves the published exe carries its own screens, without
/// opening a window or touching the network.
///
/// This exists because the worst failure the installer can produce is the quiet
/// one -- everything installs, and the window comes up empty because the page
/// that was supposed to be inside the exe is not. The build machine is a
/// different machine from the user's, so the check runs the real published
/// binary, under the same single-file packaging the user gets, and looks at what
/// its assembly actually carries.
/// </summary>
internal static class SelfCheck
{
    private static readonly string[] Required = { "index.html", "app.css", "app.js" };

    /// <summary>0 when the interface is present, non-zero otherwise.</summary>
    public static int Run()
    {
        var assembly = typeof(UiAssets).Assembly;
        var names = assembly.GetManifestResourceNames();

        var missing = Required
            .Where(file => !names.Contains("ui/" + file, StringComparer.Ordinal))
            .ToList();

        if (missing.Count == 0)
        {
            // Present is not the same as readable: a resource can be listed and
            // still be empty, which is just a blank window with extra steps.
            foreach (var file in Required)
            {
                var stream = assembly.GetManifestResourceStream("ui/" + file);
                if (stream == null || stream.Length == 0) missing.Add(file + " (empty)");
            }
        }

        if (missing.Count == 0)
        {
            Console.Out.WriteLine("ok");
            return 0;
        }

        var report =
            "the interface is not embedded: " + string.Join(", ", missing) + Environment.NewLine +
            "resources: " + string.Join(", ", names) + Environment.NewLine;

        Console.Error.Write(report);
        try
        {
            File.WriteAllText(
                Path.Combine(AppContext.BaseDirectory, "selfcheck.txt"), report);
        }
        catch
        {
            // Diagnostics are a courtesy; the exit code is the verdict.
        }

        return 2;
    }
}