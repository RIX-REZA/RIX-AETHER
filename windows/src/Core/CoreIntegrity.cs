using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Security.Cryptography;

namespace Rix.Windows.Core;

/// <summary>What went wrong before, during, or instead of starting the core.</summary>
public enum CoreStartIssue
{
    None,
    MissingCore,       // core\aether.exe is not there
    CorruptedCore,     // aether.sha256 does not match aether.exe
    MissingSignature,  // the core has no Authenticode signature (informational)
    BlockedByPolicy,   // Windows refused to run it (WDAC / Smart App Control / access)
    MissingDependency, // a DLL the process needs is not present
    InvalidImage,      // not a valid Windows executable
    NeedsElevation,    // the file demands elevation this process does not have
    Other,
}

/// <summary>Authenticode state of a PE file, read straight from Windows trust.</summary>
public enum CoreSignatureState
{
    Unknown,
    Trusted,
    NotSigned,
    HashMismatch,
    NotTrusted,
    Revoked,
    Error,
}

/// <summary>
/// Integrity and signature checks for the bundled core, plus a translator from
/// a failed process start to a reason the user can act on.
///
/// The release pipeline pins the core's SHA-256 and ships it as
/// <c>core\aether.sha256</c> beside the executable; a dev/portable build without
/// that sidecar is not treated as broken. Signing is never faked here: this code
/// only reports what Windows itself says the signature is.
/// </summary>
public static class CoreIntegrity
{
    // WINTRUST constants.
    private const uint WTD_UI_NONE = 2;
    private const uint WTD_REVOKE_NONE = 0;
    private const uint WTD_CHOICE_FILE = 1;
    private const uint WTD_STATEACTION_VERIFY = 1;
    private const uint WTD_STATEACTION_CLOSE = 2;

    private static readonly Guid WintrustActionGenericVerifyV2 =
        new("00AAC56B-CD44-11d0-8CC2-00C04FC295EE");

    // Well-known trust results.
    private const int TrustENosignature = unchecked((int)0x800B0100);
    private const int TrustESubjectNotTrusted = unchecked((int)0x800B0004);
    private const int CertEUntrustedRoot = unchecked((int)0x800B0109);
    private const int TrustEExplicitDistrust = unchecked((int)0x800B0111);
    private const int TrustEBadDigest = unchecked((int)0x80096010);
    private const int CertERevoked = unchecked((int)0x800B010C);
    private const int StatusAccessDenied = unchecked((int)0xC0000022);
    private const int StatusInvalidImageFormat = unchecked((int)0xC000007B);

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WINTRUST_FILE_INFO
    {
        public uint cbStruct;
        public IntPtr pcwszFilePath;
        public IntPtr hFile;
        public IntPtr pgKnownSubject;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct WINTRUST_DATA
    {
        public uint cbStruct;
        public IntPtr pPolicyCallbackData;
        public IntPtr pSIPClientData;
        public uint dwUIChoice;
        public uint fdwRevocationChecks;
        public uint dwUnionChoice;
        public IntPtr pInfo;
        public uint dwStateAction;
        public IntPtr hWVTStateData;
        public IntPtr pwszURLReference;
        public uint dwProvFlags;
        public uint dwUIContext;
    }

    [DllImport("wintrust.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern int WinVerifyTrust(IntPtr hwnd, ref Guid actionId, ref WINTRUST_DATA data);

    /// <summary>
    /// SHA-256 of a file in lowercase hex, or <c>null</c> when it cannot be read.
    /// </summary>
    public static string ComputeSha256(string path)
    {
        try
        {
            using var stream = File.OpenRead(path);
            using var sha = SHA256.Create();
            return Convert.ToHexString(sha.ComputeHash(stream)).ToLowerInvariant();
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// Verifies the executable against the <c>aether.sha256</c> sidecar the
    /// release pipeline ships next to it. A missing sidecar is a dev/portable
    /// build and is not treated as a failure.
    /// </summary>
    public static (bool Verified, bool HasSidecar, string Detail) VerifyAgainstSidecar(string exePath)
    {
        var sidecar = Path.ChangeExtension(exePath, ".sha256");
        if (!File.Exists(sidecar))
        {
            return (true, false, "no core\\aether.sha256 sidecar — hash not enforced (dev/portable build)");
        }

        var expected = File.ReadAllText(sidecar).Trim();
        var actual = ComputeSha256(exePath);
        if (string.IsNullOrEmpty(expected) || string.IsNullOrEmpty(actual))
        {
            return (true, true, "core\\aether.sha256 could not be read — hash check skipped");
        }

        if (!string.Equals(expected, actual, StringComparison.OrdinalIgnoreCase))
        {
            return (false, true,
                "core hash mismatch (" + Shorten(actual) + " != sidecar " + Shorten(expected) +
                ") — the core is corrupted or was replaced");
        }

        return (true, true, "core hash verified (" + actual + ")");
    }

    /// <summary>What Windows itself thinks of the file's Authenticode signature.</summary>
    public static CoreSignatureState GetSignatureState(string path)
    {
        if (string.IsNullOrEmpty(path) || !File.Exists(path)) return CoreSignatureState.Unknown;

        var fileName = Marshal.StringToHGlobalUni(path);
        var fileInfo = new WINTRUST_FILE_INFO
        {
            cbStruct = (uint)Marshal.SizeOf<WINTRUST_FILE_INFO>(),
            pcwszFilePath = fileName,
        };
        var infoPtr = Marshal.AllocHGlobal(Marshal.SizeOf<WINTRUST_FILE_INFO>());
        Marshal.StructureToPtr(fileInfo, infoPtr, false);

        var data = new WINTRUST_DATA
        {
            cbStruct = (uint)Marshal.SizeOf<WINTRUST_DATA>(),
            dwUIChoice = WTD_UI_NONE,
            fdwRevocationChecks = WTD_REVOKE_NONE,
            dwUnionChoice = WTD_CHOICE_FILE,
            pInfo = infoPtr,
            dwStateAction = WTD_STATEACTION_VERIFY,
        };

        var state = CoreSignatureState.Error;
        try
        {
            var action = WintrustActionGenericVerifyV2;
            var hr = WinVerifyTrust(IntPtr.Zero, ref action, ref data);
            state = hr switch
            {
                0 => CoreSignatureState.Trusted,
                TrustENosignature => CoreSignatureState.NotSigned,
                TrustEBadDigest => CoreSignatureState.HashMismatch,
                TrustESubjectNotTrusted or CertEUntrustedRoot or TrustEExplicitDistrust => CoreSignatureState.NotTrusted,
                CertERevoked => CoreSignatureState.Revoked,
                _ => CoreSignatureState.Error,
            };
        }
        catch
        {
            state = CoreSignatureState.Error;
        }
        finally
        {
            try
            {
                data.dwStateAction = WTD_STATEACTION_CLOSE;
                var action = WintrustActionGenericVerifyV2;
                WinVerifyTrust(IntPtr.Zero, ref action, ref data);
            }
            catch
            {
                // Diagnostics only; the verdict above already stands.
            }
            Marshal.FreeHGlobal(infoPtr);
            Marshal.FreeHGlobal(fileName);
        }

        return state;
    }

    /// <summary>Bilingual text for a signature state, for the log.</summary>
    public static string SignatureText(CoreSignatureState state)
    {
        return state switch
        {
            CoreSignatureState.Trusted => "هسته دارای امضای Authenticode معتبر است / the core has a valid Authenticode signature",
            CoreSignatureState.NotSigned => "هسته امضا نشده است / the core is not signed — Windows Application Control may refuse to run it",
            CoreSignatureState.HashMismatch => "امضای هسته با محتوای فایل سازگار نیست / the core signature does not match the file",
            CoreSignatureState.NotTrusted => "امضای هسته مورد اعتماد ویندوز نیست / the core signature is not trusted by Windows",
            CoreSignatureState.Revoked => "امضای هسته باطل شده است / the core signature has been revoked",
            CoreSignatureState.Unknown => "وضعیت امضای هسته مشخص نیست / the core signature state is unknown",
            _ => "وضعیت امضای هسته قابل بررسی نبود / the core signature could not be checked",
        };
    }

    /// <summary>
    /// Turns a failed <see cref="System.Diagnostics.Process.Start"/> exception into
    /// the most useful category. Windows Application Control / WDAC usually
    /// surfaces as an access-denied style failure.
    /// </summary>
    public static CoreStartIssue StartFailure(Exception ex)
    {
        int code = 0;
        if (ex is Win32Exception win32 && win32.NativeErrorCode != 0) code = win32.NativeErrorCode;
        else code = ex.HResult;

        return code switch
        {
            2 or 3 => CoreStartIssue.MissingCore,
            126 or 127 => CoreStartIssue.MissingDependency,
            193 or StatusInvalidImageFormat => CoreStartIssue.InvalidImage,
            740 => CoreStartIssue.NeedsElevation,
            5 or StatusAccessDenied => CoreStartIssue.BlockedByPolicy,
            _ when ex.Message?.Contains("denied", StringComparison.OrdinalIgnoreCase) == true => CoreStartIssue.BlockedByPolicy,
            _ => CoreStartIssue.Other,
        };
    }

    /// <summary>Bilingual, user-facing text for each start issue.</summary>
    public static string Describe(CoreStartIssue issue)
    {
        return issue switch
        {
            CoreStartIssue.MissingCore =>
                "فایل هسته پیدا نشد / the core was not found beside the app (core\\aether.exe)",
            CoreStartIssue.CorruptedCore =>
                "یکپارچگی هسته تأیید نشد (aether.sha256) — فایل خراب است یا جایگزین شده / core integrity check failed — the core is corrupted or was replaced",
            CoreStartIssue.MissingSignature =>
                "هسته امضای Authenticode ندارد — احتمالأ توسط Application Control ویندوز بلاک می‌شود / the core is unsigned — Windows Application Control may block it",
            CoreStartIssue.BlockedByPolicy =>
                "ویندوز اجازه اجرای هسته را نداد (Application Control / WDAC / دسترسی رد شد) / Windows refused to run the core — Application Control / WDAC blocked it",
            CoreStartIssue.MissingDependency =>
                "وابستگی موردنیاز هسته پیدا نشد (یک DLL یا اجزای اجرا ناقص است) / a required core dependency is missing",
            CoreStartIssue.InvalidImage =>
                "فایل هسته یک برنامه‌ی معتبر ویندوز نیست / the core is not a valid Windows executable",
            CoreStartIssue.NeedsElevation =>
                "اجرای هسته به سطح دسترسی بالاتر نیاز دارد / the core needs elevation to start",
            _ =>
                "هسته اجرا نشد / the core could not be started",
        };
    }

    private static string Shorten(string hex) =>
        hex.Length <= 12 ? hex : hex[..12] + "…";
}
