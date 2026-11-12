RIX-AETHER — Portable Edition
=============================

No installation is required. Extract the archive to a folder you can write to,
then run RIX.exe.

Portable behaviour
------------------
The file "portable.marker" beside RIX.exe turns on portable mode. Settings,
logs, the WebView profile and the Psiphon identity are written to the "data"
folder beside RIX.exe, not to %APPDATA%\RIX. To move the app, move the whole
folder. To remove it, delete the whole folder.

Windows requirements
--------------------
- Windows 10 version 1809 (build 17763) or newer, or Windows 11, x64.
- Microsoft Edge WebView2 Runtime. Windows 11 and up-to-date Windows 10 already
  include it. If RIX starts with an empty window, run
  MicrosoftEdgeWebview2Setup.exe from this folder once, then start RIX again.
- No .NET runtime installation is needed: RIX.exe is self-contained.

The core tunnel program is in core\aether.exe. RIX verifies its SHA-256
against core\aether.sha256 before launching it. Do not move files out of the
package.

If Windows SmartScreen shows a warning, the build is unsigned (no code-signing
certificate was configured for this release). Verify the SHA-256 checksum
published with the release before running it.

Desktop editions
----------------
If you want Start Menu shortcuts and a normal uninstall entry, use the
Windows 10 or Windows 11 Desktop Setup instead. Installing over an older RIX
version keeps your settings in %APPDATA%\RIX.
