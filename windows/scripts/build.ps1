<#
.SYNOPSIS
  Builds RIX for one Windows architecture.

.DESCRIPTION
  Produces a self-contained single-file RIX.exe, the elevated tunnel helper,
  and either a portable ZIP or (with -Installer) an Inno Setup installer. The
  Aether core is architecture-specific; pass -CoreDir pointing at a folder that
  contains aether.exe for the same RID. Without it the app builds and starts but
  reports that the core is missing, which is the honest outcome rather than a
  binary that pretends to tunnel.

  Examples:
    ./build.ps1 -Runtime win-x64 -Installer
    ./build.ps1 -Runtime win-arm64 -CoreDir C:\aether-arm64
    ./build-all.ps1 -Edition 10
#>
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('win-x86', 'win-x64', 'win-arm64')]
    [string] $Runtime,

    [string] $Version = '1.0.7',

    # Folder containing aether.exe (and optionally pt\, LICENSES\).
    [string] $CoreDir = '',

    [switch] $Installer,

    # Windows 10 (17763) or Windows 11 (22000) installer floor.
    [ValidateSet('10', '11')]
    [string] $Edition = '10'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$repo = Split-Path -Parent $root
$publish = Join-Path $root "publish/$Runtime"
$dist = Join-Path $root 'dist'

Write-Host "== RIX $Version / $Runtime ==" -ForegroundColor Cyan
Remove-Item $publish -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $publish | Out-Null
New-Item -ItemType Directory -Force $dist | Out-Null

# ---- the app
dotnet publish (Join-Path $root 'src/Rix.Windows.csproj') `
    -c Release -r $Runtime --self-contained true `
    -p:Version=$Version -p:DebugType=None -p:DebugSymbols=false `
    -o $publish --nologo
if ($LASTEXITCODE -ne 0) { throw "app publish failed for $Runtime" }

# ---- the elevated helper, beside the core
$coreOut = Join-Path $publish 'core'
New-Item -ItemType Directory -Force $coreOut | Out-Null
dotnet publish (Join-Path $root 'src/tunnel/Rix.Tunnel.csproj') `
    -c Release -r $Runtime --self-contained true `
    -p:Version=$Version -p:DebugType=None -p:DebugSymbols=false `
    -o $coreOut --nologo
if ($LASTEXITCODE -ne 0) { throw "tunnel helper publish failed for $Runtime" }

# The helper is published with its own runtime files when single-file is off
# for a RID; keep only what the app needs.
Get-ChildItem $coreOut -File |
    Where-Object { $_.Name -notin @('RIX-Tunnel.exe', 'hev-socks5-tunnel.exe', 'wintun.dll', 'msys-2.0.dll') } |
    Remove-Item -Force

# ---- the core
if ($CoreDir) {
    Copy-Item (Join-Path $CoreDir 'aether.exe') (Join-Path $coreOut 'aether.exe') -Force
    if (Test-Path (Join-Path $CoreDir 'pt')) {
        Copy-Item (Join-Path $CoreDir 'pt') (Join-Path $coreOut 'pt') -Recurse -Force
    }
    if (Test-Path (Join-Path $CoreDir 'LICENSES')) {
        Copy-Item (Join-Path $CoreDir 'LICENSES') (Join-Path $coreOut 'LICENSES') -Recurse -Force
    }
    # Optional Full Tunnel payload supplied beside the core (x64 only today).
    foreach ($name in @('hev-socks5-tunnel.exe', 'wintun.dll', 'msys-2.0.dll')) {
        $source = Join-Path $CoreDir $name
        if (Test-Path $source) { Copy-Item $source (Join-Path $coreOut $name) -Force }
    }
    $hash = (Get-FileHash (Join-Path $coreOut 'aether.exe') -Algorithm SHA256).Hash.ToLower()
    Set-Content (Join-Path $coreOut 'aether.sha256') $hash -NoNewline
    Write-Host "core sha256: $hash"
}

# ---- WebView2 bootstrapper, once per publish folder
$setup = Join-Path $publish 'MicrosoftEdgeWebview2Setup.exe'
if (-not (Test-Path $setup)) {
    Invoke-WebRequest -Uri 'https://go.microsoft.com/fwlink/p/?LinkId=2124703' -OutFile $setup
}

# ---- portable
$name = "RIX-AETHER-Windows-$Edition-$Runtime-Portable"
$stage = Join-Path $dist $name
Remove-Item $stage -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item (Join-Path $publish 'RIX.exe') $stage
Copy-Item $coreOut (Join-Path $stage 'core') -Recurse
Copy-Item $setup $stage
Copy-Item (Join-Path $root 'portable.marker') (Join-Path $stage 'portable.marker')
Copy-Item (Join-Path $root 'PORTABLE-README.txt') (Join-Path $stage 'README-PORTABLE.txt')
Copy-Item (Join-Path $repo 'LICENSE') (Join-Path $stage 'LICENSE.txt')
Copy-Item (Join-Path $repo 'NOTICE') (Join-Path $stage 'NOTICE.txt')
$zip = Join-Path $dist "$name.zip"
Compress-Archive -Path "$stage/*" -DestinationPath $zip -Force
Write-Host "portable: $zip"

# ---- installer
if ($Installer) {
    $iscc = Join-Path ${env:ProgramFiles(x86)} 'Inno Setup 6/ISCC.exe'
    if (-not (Test-Path $iscc)) {
        $iscc = (Get-Command ISCC.exe -ErrorAction SilentlyContinue).Source
    }
    if (-not $iscc) { throw 'ISCC.exe not found; install Inno Setup 6' }

    $arch = switch ($Runtime) {
        'win-x86' { @{ Allowed = 'x86compatible'; Mode = '' } }
        'win-arm64' { @{ Allowed = 'arm64'; Mode = 'arm64' } }
        default { @{ Allowed = 'x64compatible'; Mode = 'x64compatible' } }
    }
    $min = if ($Edition -eq '11') { '10.0.22000' } else { '10.0.17763' }
    & $iscc `
        "/DOutputBaseFilename=RIX-AETHER-Windows-$Edition-$Runtime-Desktop-Setup" `
        "/DMinWindows=$min" `
        "/DAllowedArch=$($arch.Allowed)" `
        "/DInstall64BitMode=$($arch.Mode)" `
        "/DPublishDir=$publish" `
        (Join-Path $root 'installer/Rix.iss')
    if ($LASTEXITCODE -ne 0) { throw "installer failed for $Runtime" }
}
