namespace Rix.Windows;

/// <summary>
/// The scanner links against AppState only for its data directory. The test
/// build supplies this minimal equivalent so the module runs on Linux and on a
/// Windows runner that never creates the real app's folders.
/// </summary>
public static class AppState
{
    public static string Dir { get; set; } = Path.Combine(Path.GetTempPath(), "rix-tests-" + Guid.NewGuid().ToString("N"));
}
