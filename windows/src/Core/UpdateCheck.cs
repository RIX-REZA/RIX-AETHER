using System.Text.Json;

namespace Rix.Windows.Core;

/// <summary>What an update check found, or why it found nothing.</summary>
public sealed record UpdateInfo(bool Available, string Version, string Url, string Error)
{
    public static UpdateInfo None() => new(false, "", "", null);
}

/// <summary>
/// The update check behind the one secondary button on the home screen.
///
/// It reads the public repository's latest release and compares the tag to
/// the running assembly's own version. It deliberately downloads nothing by
/// itself: the answer is a release URL the user opens in their own browser,
/// where signatures and checksums are visible, rather than an executable
/// fetched and replaced on disk without anyone looking.
/// </summary>
public static class UpdateCheck
{
    public const string Repo = "RIX-REZA/RIX-AETHER";
    public const string ReleasesBase = "https://github.com/" + Repo + "/releases";

    /// <summary>The app's own version — the same one the installer records.</summary>
    public static Version Current =>
        typeof(UpdateCheck).Assembly.GetName().Version ?? new Version(0, 0, 0);

    public static async Task<UpdateInfo> CheckAsync(HttpClient http, CancellationToken token)
    {
        try
        {
            using var request = new HttpRequestMessage(
                HttpMethod.Get, "https://api.github.com/repos/" + Repo + "/releases/latest");
            request.Headers.UserAgent.ParseAdd("RIX/" + Current.ToString(3));

            using var response = await http.SendAsync(request, token);
            response.EnsureSuccessStatusCode();

            await using var stream = await response.Content.ReadAsStreamAsync(token);
            using var document = await JsonDocument.ParseAsync(stream, cancellationToken: token);
            var root = document.RootElement;

            var tag = root.GetProperty("tag_name").GetString() ?? "";
            var url = root.GetProperty("html_url").GetString() ?? ReleasesBase + "/latest";
            var version = ParseVersion(tag);
            if (version == null)
                return new UpdateInfo(false, "", url, null);

            return new UpdateInfo(version > Current, version.ToString(3), url, null);
        }
        catch (Exception ex)
        {
            // A check that cannot run (no network, rate limit) is "no update
            // known", with the reason, not an error dialog over the dial.
            return new UpdateInfo(false, "", "", ex.Message);
        }
    }

    /// <summary>
    /// "v1.0.7", "1.0.7", "v1.0.7-beta" all become 1.0.7. A tag the app cannot
    /// read is not an update, rather than a crash.
    /// </summary>
    internal static Version ParseVersion(string tag)
    {
        if (string.IsNullOrWhiteSpace(tag)) return null;
        var text = tag.Trim().TrimStart('v', 'V');
        var parts = text.Split('.');
        var numbers = new List<int>();
        foreach (var part in parts)
        {
            var digits = new string(part.TakeWhile(char.IsDigit).ToArray());
            if (!int.TryParse(digits, out var number)) break;
            numbers.Add(number);
        }

        if (numbers.Count < 2) return null;
        while (numbers.Count < 4) numbers.Add(0);
        return new Version(numbers[0], numbers[1], numbers[2], numbers[3]);
    }
}
