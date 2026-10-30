using System.Runtime.InteropServices;

namespace Rix.Windows.Core;

/// <summary>
/// A Windows job object the core is put in, set to kill everything in it when
/// the job closes.
///
/// The point is the Psiphon child: aether.exe spawns
/// <c>pt\psiphon-tunnel-core.exe</c> and, if RIX is killed outright -- the user
/// ends the task, the machine shuts down mid-connect, an unhandled crash -- the
/// child would otherwise be left behind, holding ports 1821/1822 and keeping a
/// tunnel up with nothing to control it. That is exactly the "the app is off but
/// the tunnel is still there" failure the phone had, and on Windows the kernel
/// can rule it out rather than the app having to remember.
/// </summary>
internal sealed class JobObject : IDisposable
{
    private const int JobObjectExtendedLimitInformation = 9;
    private const uint JobObjectLimitKillOnJobClose = 0x2000;

    private IntPtr _handle;

    public JobObject()
    {
        _handle = CreateJobObject(IntPtr.Zero, null);
        if (_handle == IntPtr.Zero) return;

        var limits = new JobObjectExtendedLimitInformation_
        {
            BasicLimitInformation = new JobObjectBasicLimitInformation
            {
                LimitFlags = JobObjectLimitKillOnJobClose,
            },
        };

        var size = Marshal.SizeOf<JobObjectExtendedLimitInformation_>();
        var ptr = Marshal.AllocHGlobal(size);
        try
        {
            Marshal.StructureToPtr(limits, ptr, false);
            if (!SetInformationJobObject(_handle, JobObjectExtendedLimitInformation, ptr, (uint)size))
            {
                // Without the limit the object is pointless; drop it so the
                // caller falls back to killing the tree by hand.
                CloseHandle(_handle);
                _handle = IntPtr.Zero;
            }
        }
        finally
        {
            Marshal.FreeHGlobal(ptr);
        }
    }

    public bool IsValid => _handle != IntPtr.Zero;

    /// <summary>
    /// Attaches a started process. Best effort: a failure only means the
    /// belt-and-braces cleanup is missing, and the explicit kill still runs.
    /// </summary>
    public void Add(IntPtr processHandle)
    {
        if (_handle == IntPtr.Zero || processHandle == IntPtr.Zero) return;
        AssignProcessToJobObject(_handle, processHandle);
    }

    public void Dispose()
    {
        if (_handle == IntPtr.Zero) return;
        // Closing the last handle to the job is what triggers the kill, so this
        // is the cleanup and not merely a release.
        CloseHandle(_handle);
        _handle = IntPtr.Zero;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JobObjectBasicLimitInformation
    {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public UIntPtr MinimumWorkingSetSize;
        public UIntPtr MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public UIntPtr Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IoCounters
    {
        public ulong ReadOperationCount;
        public ulong WriteOperationCount;
        public ulong OtherOperationCount;
        public ulong ReadTransferCount;
        public ulong WriteTransferCount;
        public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct JobObjectExtendedLimitInformation_
    {
        public JobObjectBasicLimitInformation BasicLimitInformation;
        public IoCounters IoInfo;
        public UIntPtr ProcessMemoryLimit;
        public UIntPtr JobMemoryLimit;
        public UIntPtr PeakProcessMemoryUsed;
        public UIntPtr PeakJobMemoryUsed;
    }

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)]
    private static extern IntPtr CreateJobObject(IntPtr attributes, string name);

    [DllImport("kernel32.dll")]
    private static extern bool SetInformationJobObject(IntPtr job, int infoClass, IntPtr info, uint length);

    [DllImport("kernel32.dll")]
    private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);

    [DllImport("kernel32.dll")]
    private static extern bool CloseHandle(IntPtr handle);
}