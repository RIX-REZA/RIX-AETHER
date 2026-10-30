using Rix.Windows.Core;

namespace Rix.Windows;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        // One RIX per machine: two copies would fight over the same ports and
        // the same registry value, and the second would lose in a way that looks
        // like the app is broken.
        using var instance = new Mutex(true, @"Local\RIX-single-instance", out var isFirst);
        if (!isFirst)
        {
            MessageBox.Show(
                "RIX در حال اجراست.\n\nRIX is already running — look for it in the tray.",
                "RIX", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);

        // Before anything else: if the last run was killed while it owned the
        // system proxy, the machine is still pointing at a port with nothing
        // behind it, and no browser on it works. Put that back first.
        SystemProxy.RecoverIfNeeded();

        var state = AppState.Load();

        Application.ThreadException += (_, e) => Report(e.Exception);
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Report(e.ExceptionObject as Exception);

        var form = new MainForm(state);

        var startHidden = args.Any(a => a.Equals("--tray", StringComparison.OrdinalIgnoreCase));
        if (startHidden)
        {
            form.WindowState = FormWindowState.Minimized;
            form.ShowInTaskbar = false;
            form.Load += (_, _) => form.Hide();
            if (args.Any(a => a.Equals("--connect", StringComparison.OrdinalIgnoreCase)))
            {
                form.Load += async (_, _) => await form.AutoConnectAsync();
            }
        }

        Application.Run(form);
    }

    private static void Report(Exception exception)
    {
        if (exception == null) return;
        try
        {
            Directory.CreateDirectory(AppState.Dir);
            File.AppendAllText(
                Path.Combine(AppState.Dir, "rix-errors.log"),
                DateTime.Now.ToString("u") + "  " + exception + Environment.NewLine);
        }
        catch
        {
        }

        // Never a silent disappearance: whatever went wrong, the user is told
        // where the detail was written.
        MessageBox.Show(
            "خطای غیرمنتظره / unexpected error:\n\n" + exception.Message +
            "\n\n" + Path.Combine(AppState.Dir, "rix-errors.log"),
            "RIX", MessageBoxButtons.OK, MessageBoxIcon.Error);
    }
}