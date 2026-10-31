using System.Diagnostics;
using System.Text;
using System.Text.RegularExpressions;

namespace Rix.Windows.Core;

/// <summary>One reading of the core's own counters.</summary>
public readonly record struct CoreStats(long Up, long Down, TimeSpan Uptime);

/// <summary>
/// The core, as a child process.
///
/// Everything the app knows about the tunnel comes from here: the log lines it
/// prints and the counters it emits with <c>--stats</c>, so the numbers under the
/// orb are the core's own rather than a guess made by adding up bytes the app
/// never sees. A SOCKS5 listener that accepts is not proof of a tunnel, so the
/// "connected" verdict comes from <see cref="Probe"/> instead, exactly as on the
/// phone.
/// </summary>
public sealed class AetherProcess : IDisposable
{
    /// <summary>
    /// <c>[=] up 1.2 MiB down 512 B uptime 00:00:12</c>, or <c>uptime 1d 01:01:01</c>
    /// once it has been up for a day.
    /// </summary>
    private static readonly Regex StatsLine = new(
        @"up\s+(?<up>[\d.]+)\s*(?<uu>[KMGT]i?B|B)\s+" +
        @"down\s+(?<down>[\d.]+)\s*(?<du>[KMGT]i?B|B)\s+" +
        @"uptime\s+(?<t>(?:\d+d\s+)?\d+:\d+:\d+)",
        RegexOptions.Compiled | RegexOptions.IgnoreCase);

    private readonly object _gate = new();
    private readonly List<string> _tail = new();
    private Process _process;
    private JobObject _job;
    private EventHandler _onExit;

    /// <summary>
    /// One process's lifetime, and whether the app ended it.
    ///
    /// The exit event is raised on a thread-pool thread and can arrive after a
    /// replacement has already started -- that race is what made a fallback
    /// attempt look like "the core stopped" and cancelled the attempt that was
    /// still running. A token belongs to exactly one start, so an event that
    /// names a token nobody is holding any more is a stop the app asked for and
    /// is dropped rather than reported.
    /// </summary>
    private sealed class Run
    {
        public bool Ended;
    }

    private Run _run;

    /// <summary>Every line the core prints, already timestamped.</summary>
    public event Action<string> LogLine;

    /// <summary>Fires about once a second while the core is up.</summary>
    public event Action<CoreStats> StatsChanged;

    /// <summary>Fires once, with the exit code, whenever the child ends.</summary>
    public event Action<int> Exited;

    public bool IsRunning
    {
        get
        {
            lock (_gate)
            {
                try { return _process is { HasExited: false }; }
                catch { return false; }
            }
        }
    }

    /// <summary>The last few lines, for a panel opened after the fact.</summary>
    public IReadOnlyList<string> Tail
    {
        get { lock (_gate) return _tail.ToArray(); }
    }

    /// <summary>
    /// Where the core lives: beside the app, under <c>core\</c>. The installer
    /// puts it there and this is the only place that decides.
    /// </summary>
    public static string Locate()
    {
        var beside = Path.Combine(AppContext.BaseDirectory, "core", "aether.exe");
        if (File.Exists(beside)) return beside;

        // A developer running from a build tree, or an unpacked zip.
        var local = Path.Combine(AppContext.BaseDirectory, "aether.exe");
        if (File.Exists(local)) return local;

        return null;
    }

    /// <summary>
    /// Starts the core. Returns false when it could not be launched at all --
    /// a missing exe, a bad path -- and lets a non-zero exit code surface
    /// through <see cref="Exited"/> rather than throwing, so the UI can show the
    /// reason instead of an exception.
    /// </summary>
    public bool Start(string exePath, IEnumerable<string> args, string workingDirectory)
    {
        Stop();

        var info = new ProcessStartInfo
        {
            FileName = exePath,
            WorkingDirectory = workingDirectory,
            UseShellExecute = false,
            CreateNoWindow = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            RedirectStandardInput = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        foreach (var arg in args) info.ArgumentList.Add(arg);

        var process = new Process { StartInfo = info, EnableRaisingEvents = true };
        process.OutputDataReceived += (_, e) => OnLine(e.Data);
        process.ErrorDataReceived += (_, e) => OnLine(e.Data);

        // The token is claimed before the child exists, so an exit that happens
        // immediately still finds the run it belongs to.
        var run = new Run();
        lock (_gate) _run = run;

        // Kept in a field so a deliberate stop can detach it: an exit the app
        // asked for is not news, and reporting it would turn every fallback
        // attempt into a failure the user is told about.
        EventHandler onExit = (_, _) => OnExited(run, process);
        process.Exited += onExit;

        try
        {
            if (!process.Start()) return false;
        }
        catch (Exception ex)
        {
            Emit("core: cannot start " + exePath + " -- " + ex.Message);
            lock (_gate) { if (ReferenceEquals(_run, run)) _run = null; }
            process.Dispose();
            return false;
        }

        // A stop can land between the child being created and this line: the
        // disconnect runs on another thread and saw no process to stop. Taking
        // the child anyway would leave a core nobody is watching -- holding the
        // socks port and the next attempt's listener -- so a lifetime that has
        // already ended kills its own child and reports the start as failed.
        var abandoned = false;
        lock (_gate)
        {
            if (run.Ended || !ReferenceEquals(_run, run))
            {
                abandoned = true;
            }
            else
            {
                _process = process;
                _onExit = onExit;
                _tail.Clear();
            }
        }
        if (abandoned)
        {
            try
            {
                process.Kill(entireProcessTree: true);
                process.WaitForExit(2000);
            }
            catch { /* it may have exited on its own */ }
            process.Dispose();
            Emit("core: stopped before it was ready; discarded");
            return false;
        }

        // Before the first byte is read: a child started between these two lines
        // would escape the job, and psiphon is exactly such a child.
        var job = new JobObject();
        if (job.IsValid)
        {
            try { job.Add(process.Handle); }
            catch { /* the explicit kill below still covers it */ }
        }
        _job = job;

        process.BeginOutputReadLine();
        process.BeginErrorReadLine();

        Emit("core: started " + exePath);
        Emit("core: " + string.Join(' ', args));
        return true;
    }

    /// <summary>
    /// Stops the core: ask first, kill after.
    ///
    /// The polite attempt is a closed stdin, which is how the core is told to
    /// leave; the kill is what guarantees it, and it takes the Psiphon child
    /// with it. Called from a background thread because it can wait a moment.
    /// </summary>
    public void Stop()
    {
        Process process;
        JobObject job;
        EventHandler onExit;
        lock (_gate)
        {
            process = _process;
            _process = null;
            onExit = _onExit;
            _onExit = null;
            job = _job;
            _job = null;
            // The lifetime ends here: any exit event still in flight belongs to
            // a run nobody is waiting on, and reporting it would be a failure
            // the app caused itself.
            var run = _run;
            if (run != null) run.Ended = true;
            _run = null;
        }

        if (process != null)
        {
            if (onExit != null)
            {
                try { process.Exited -= onExit; } catch { }
            }
            try
            {
                if (!process.HasExited)
                {
                    try
                    {
                        process.StandardInput.Write("quit\n");
                        process.StandardInput.Flush();
                        process.StandardInput.Close();
                    }
                    catch { /* no stdin, or already gone */ }

                    if (!process.WaitForExit(1500))
                    {
                        Emit("core: did not exit on its own, killing it");
                        process.Kill(entireProcessTree: true);
                        process.WaitForExit(4000);
                    }
                }
            }
            catch (Exception ex)
            {
                Emit("core: stop -- " + ex.Message);
            }
            finally
            {
                try { process.Dispose(); } catch { }
            }
        }

        // Closing the job kills anything the core left behind -- the Psiphon
        // child in particular.
        job?.Dispose();
    }

    /// <summary>
    /// The child ended. Only a run this object still holds is news; one the app
    /// stopped, or one a newer start replaced, is dropped -- the event can be
    /// raised a moment after either.
    /// </summary>
    private void OnExited(Run run, Process process)
    {
        int code;
        try { code = process.ExitCode; } catch { code = -1; }

        lock (_gate)
        {
            if (!ReferenceEquals(_run, run) || run.Ended) return;
            _run = null;
            if (ReferenceEquals(_process, process)) _process = null;
        }

        Emit("core: exited with code " + code);
        Exited?.Invoke(code);
    }

    private void OnLine(string line)
    {
        if (string.IsNullOrWhiteSpace(line)) return;

        var match = StatsLine.Match(line);
        if (match.Success)
        {
            // Counters are drawn every second and would bury the log.
            StatsChanged?.Invoke(new CoreStats(
                Scale(match.Groups["up"].Value, match.Groups["uu"].Value),
                Scale(match.Groups["down"].Value, match.Groups["du"].Value),
                Uptime(match.Groups["t"].Value)));
            return;
        }

        Emit(line.Trim());
        LogLine?.Invoke(line.Trim());
    }

    private void Emit(string line)
    {
        var stamped = DateTime.Now.ToString("HH:mm:ss") + "  " + line;
        lock (_gate)
        {
            _tail.Add(stamped);
            if (_tail.Count > 400) _tail.RemoveRange(0, _tail.Count - 400);
        }
        LogLine?.Invoke(stamped);
    }

    /// <summary>Bytes, from the core's "1.2 MiB" or "512 B".</summary>
    private static long Scale(string number, string unit)
    {
        if (!double.TryParse(number, System.Globalization.NumberStyles.Float,
                System.Globalization.CultureInfo.InvariantCulture, out var value))
            return 0;
        var factor = unit?.ToUpperInvariant() switch
        {
            "KIB" or "KB" or "K" => 1024d,
            "MIB" or "MB" or "M" => 1024d * 1024,
            "GIB" or "GB" or "G" => 1024d * 1024 * 1024,
            "TIB" or "TB" or "T" => 1024d * 1024 * 1024 * 1024,
            _ => 1d,
        };
        return (long)(value * factor);
    }

    /// <summary>Elapsed time, from "00:00:12" or the core's "1d 01:01:01".</summary>
    private static TimeSpan Uptime(string text)
    {
        var days = 0;
        var rest = text.Trim();
        var d = rest.IndexOf('d');
        if (d > 0 && int.TryParse(rest[..d], out var parsedDays))
        {
            days = parsedDays;
            rest = rest[(d + 1)..].Trim();
        }
        if (!TimeSpan.TryParse(rest, System.Globalization.CultureInfo.InvariantCulture, out var span))
            return TimeSpan.Zero;
        return span + TimeSpan.FromDays(days);
    }

    public void Dispose()
    {
        Stop();
        GC.SuppressFinalize(this);
    }
}