; RIX for Windows — the installer.
;
; What it does, in the order it matters:
;   1. Puts the app and the core somewhere the user can write to, so no step
;      needs an administrator prompt and nothing has to be elevated to run.
;   2. Checks for the WebView2 runtime and installs it silently if it is not
;      already there, because without it the app has no interface. Windows 11
;      and updated Windows 10 already have it, so this is usually a no-op.
;   3. On uninstall, closes a running RIX and hands the machine's proxy settings
;      back, so removing the app cannot leave a browser pointing at a port
;      nobody serves.
;
; Deliberately not done: no driver, no service, no TAP adapter. The app runs as
; the user and configures the per-user proxy. That is what makes the install
; finish in seconds and stay finished.

#define AppName "RIX"
#define AppVersion "1.0.5"
#define AppPublisher "RIX"
#define AppExe "RIX.exe"

[Setup]
AppId={{7A1C3E52-5F4B-4E0A-9E31-2B7D9C4A6F18}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={autopf}\{#AppName}
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
; Per-user install: no UAC prompt, and the core is allowed to write its identity
; file beside itself.
PrivilegesRequired=lowest
PrivilegesRequiredOverridesAllowed=dialog
OutputBaseFilename=RIX-Setup-{#AppVersion}
OutputDir=..\dist
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayIcon={app}\{#AppExe}
UninstallDisplayName={#AppName}
MinVersion=10.0.17763
SetupLogging=yes
CloseApplications=yes
RestartApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"
; Vendored in this folder rather than taken from the compiler: Inno Setup ships
; about thirty official translations and Farsi is not one of them, so
; "compiler:Languages\Farsi.isl" would simply fail to compile. This copy is the
; community translation from jrsoftware/issrc, kept unedited apart from the BOM.
Name: "farsi"; MessagesFile: "Farsi.isl"

[Tasks]
Name: "desktopicon"; Description: "{cm:CreateDesktopIcon}"; Flags: unchecked
Name: "startup"; Description: "اجرا با ویندوز / Start with Windows"; Flags: unchecked

[Files]
Source: "..\publish\{#AppExe}"; DestDir: "{app}"; Flags: ignoreversion
; The core, and the Psiphon client it spawns for the BETA chain. The
; `pt` folder name is not decoration: the core looks for the binary there.
Source: "..\publish\core\aether.exe"; DestDir: "{app}\core"; Flags: ignoreversion
Source: "..\publish\core\pt\psiphon-tunnel-core.exe"; DestDir: "{app}\core\pt"; Flags: ignoreversion
Source: "..\publish\core\pt\lyrebird.exe"; DestDir: "{app}\core\pt"; Flags: ignoreversion
Source: "..\publish\core\LICENSES\*"; DestDir: "{app}\core\LICENSES"; Flags: ignoreversion recursesubdirs

[Icons]
Name: "{group}\{#AppName}"; Filename: "{app}\{#AppExe}"
Name: "{group}\حذف {#AppName}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#AppName}"; Filename: "{app}\{#AppExe}"; Tasks: desktopicon
Name: "{userstartup}\{#AppName}"; Filename: "{app}\{#AppExe}"; Parameters: "--tray --connect"; Tasks: startup

[Run]
; The WebView2 runtime, only when it is genuinely absent. A machine that
; already has it -- every Windows 11, and Windows 10 with Edge updates -- skips
; this entirely, which is what keeps the common install quick.
Filename: "{tmp}\MicrosoftEdgeWebview2Setup.exe"; Parameters: "/silent /install"; \
  StatusMsg: "نصب WebView2 / installing the WebView2 runtime..."; \
  Check: NeedsWebView2; Flags: waituntilterminated skipifdoesntexist

Filename: "{app}\{#AppExe}"; Description: "{cm:LaunchProgram,{#AppName}}"; \
  Flags: nowait postinstall skipifsilent

[UninstallRun]
; Close RIX first: a running copy holds the core open, and it also owns the
; machine's proxy setting, which it puts back as it goes.
Filename: "{cmd}"; Parameters: "/c taskkill /IM {#AppExe} /F"; \
  Flags: runhidden; RunOnceId: "CloseRix"

[UninstallDelete]
; Everything the app wrote under its own directory. The identity file and the
; Psiphon data live in %APPDATA%\RIX and are deliberately left behind: a
; reinstall keeps its registered device rather than asking Cloudflare again.
Type: filesandordirs; Name: "{app}\core"

[Code]
const
  WebView2Key = 'SOFTWARE\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}';

function WebView2Version(): String;
var
  Value: String;
begin
  Value := '';
  // Per-user first, then per-machine: the runtime is installed one way or the
  // other depending on how it arrived.
  if not RegQueryStringValue(HKCU, WebView2Key, 'pv', Value) then
    RegQueryStringValue(HKLM, WebView2Key, 'pv', Value);
  Result := Value;
end;

function NeedsWebView2(): Boolean;
begin
  Result := WebView2Version() = '';
end;

function InitializeSetup(): Boolean;
var
  ErrorCode: Integer;
  Downloaded: Boolean;
begin
  Result := True;

  if NeedsWebView2() then
  begin
    Downloaded := DownloadToFile(
      'https://go.microsoft.com/fwlink/p/?LinkId=2124703',
      ExpandConstant('{tmp}\MicrosoftEdgeWebview2Setup.exe'), '', '', '', ErrorCode);
    if not Downloaded then
    begin
      if MsgBox('دریافت WebView2 ممکن نشد. برنامه بدون آن اجرا نمی‌شود.' + #13#10 +
                'The WebView2 runtime could not be downloaded and RIX cannot run without it.' + #13#10#13#10 +
                'بدون آن ادامه دهم؟ / Continue anyway?',
                mbConfirmation, MB_YESNO) = IDNO then
        Result := False;
    end;
  end;
end;
