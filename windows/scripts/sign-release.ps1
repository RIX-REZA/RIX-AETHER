<#
.SYNOPSIS
    Optional Authenticode signing for the RIX Windows release.

.DESCRIPTION
    Signs RIX.exe, the bundled core executables (core\aether.exe and the core's
    own helpers) and, with -Installers, the Inno Setup installer, using SHA-256
    and an RFC 3161 timestamp.

    Signing only ever happens with a REAL certificate. Nothing is faked here,
    nothing claims a self-signed or missing signature is trusted, and the
    private key is never written into the repository. Configuration comes
    entirely from environment variables; when none is set the script does
    nothing and reports an unsigned (dev) build.

    Supported configurations (set exactly one):
      * Certificate in a store:        RIX_CERT_THUMBPRINT
      * PFX supplied as base64 secret: RIX_PFX_B64 (+ RIX_PFX_PASSWORD)
      * Azure Artifact Signing:        RIX_SIGN_AZURE=true plus the
                                       RIX_AZURE_* variables (endpoint, tenant,
                                       client id, client secret, cert name)
    Optional: RIX_TIMESTAMP_URL (RFC 3161 server; default timestamp.digicert.com)

.EXAMPLE
    $env:RIX_CERT_THUMBPRINT = "AB12..."
    powershell -File windows/scripts/sign-release.ps1 -Installers
#>
[CmdletBinding()]
param(
    # Also sign the installer produced by Inno Setup in windows\dist.
    [switch]$Installers
)

$ErrorActionPreference = "Stop"

function Get-SignTool {
    $cmd = Get-Command "signtool.exe" -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $root = "${env:ProgramFiles(x86)}\Windows Kits\10\bin"
    if (Test-Path $root) {
        $found = Get-ChildItem -Path $root -Recurse -Filter "signtool.exe" -ErrorAction SilentlyContinue |
            Where-Object { $_.FullName -match "\\x64\\" } | Sort-Object FullName -Descending | Select-Object -First 1
        if ($found) { return $found.FullName }
    }
    return $null
}

function Resolve-Targets {
    $targets = @()
    $app = "windows/publish/RIX.exe"
    if (Test-Path $app) { $targets += $app }
    if (Test-Path "windows/publish/core") {
        $targets += Get-ChildItem "windows/publish/core" -Filter "*.exe" -Recurse | ForEach-Object { $_.FullName }
    }
    if ($Installers) {
        $targets += Get-ChildItem "windows/dist" -Filter "RIX-Setup-*.exe" -ErrorAction SilentlyContinue |
            ForEach-Object { $_.FullName }
    }
    return ($targets | Select-Object -Unique)
}

function Assert-Signed {
    param([string[]]$Files)
    foreach ($file in $Files) {
        $sig = Get-AuthenticodeSignature $file
        if ($sig.Status -ne "Valid") {
            throw "signing requested but $file is not validly signed (status $($sig.Status))"
        }
        Write-Host ("signed ok: {0} by {1}" -f (Split-Path $file -Leaf), $sig.SignerCertificate.Subject)
    }
}

# ------------------------------------------------------------------ configure

$azure      = ($env:RIX_SIGN_AZURE -eq "true")
$thumbprint = $env:RIX_CERT_THUMBPRINT
$pfxB64     = $env:RIX_PFX_B64
$timestamp  = $env:RIX_TIMESTAMP_URL
if (-not $timestamp) { $timestamp = "http://timestamp.digicert.com" }

if (-not $azure -and -not $thumbprint -and -not $pfxB64) {
    Write-Host "signing: no certificate configured (RIX_CERT_THUMBPRINT / RIX_PFX_B64 / RIX_SIGN_AZURE are all empty)"
    Write-Host "signing: leaving the build unsigned (dev build) -- nothing is pretended to be signed."
    exit 0
}

$targets = Resolve-Targets
if ($targets.Count -eq 0) {
    throw "signing was requested but no publish output was found under windows/publish"
}

# --------------------------------------------------------------------- sign

if ($azure) {
    if (-not (Get-Command "dotnet" -ErrorAction SilentlyContinue)) {
        throw "Azure Artifact Signing was requested but dotnet (for AzureSignTool) is not installed"
    }
    $toolPath = Join-Path $PSScriptRoot ".azuresign"
    dotnet tool install --tool-path $toolPath AzureSignTool --ignore-failed-sources | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "AzureSignTool install failed" }
    $az = Join-Path $toolPath "AzureSignTool.exe"

    foreach ($endpoint in "RIX_AZURE_ENDPOINT", "RIX_AZURE_TENANT", "RIX_AZURE_CLIENT_ID",
                          "RIX_AZURE_CLIENT_SECRET", "RIX_AZURE_CERT_NAME") {
        if (-not $env:$endpoint) { throw "Azure Artifact Signing requested but $endpoint is empty" }
    }

    foreach ($file in $targets) {
        Write-Host ("signing (azure): " + $file)
        & $az sign `
            -kvu $env:RIX_AZURE_ENDPOINT `
            -kvt $env:RIX_AZURE_TENANT `
            -kvi $env:RIX_AZURE_CLIENT_ID `
            -kvs $env:RIX_AZURE_CLIENT_SECRET `
            -kvc $env:RIX_AZURE_CERT_NAME `
            -fd SHA256 -tr $timestamp -td SHA256 `
            $file
        if ($LASTEXITCODE -ne 0) { throw "AzureSignTool failed on $file" }
    }
}
else {
    $signtool = Get-SignTool
    if (-not $signtool) {
        throw "classic signing requested but signtool.exe was not found (install the Windows SDK)"
    }

    $cred = @()
    $pfxPath = $null
    if ($thumbprint) {
        $cred = @("/sha1", $thumbprint)
    }
    else {
        $pfxPath = Join-Path $env:TEMP "rix-signing.pfx"
        [IO.File]::WriteAllBytes($pfxPath, [Convert]::FromBase64String($pfxB64))
        $cred = @("/f", $pfxPath)
        if ($env:RIX_PFX_PASSWORD) { $cred += @("/p", $env:RIX_PFX_PASSWORD) }
    }

    try {
        foreach ($file in $targets) {
            Write-Host ("signing: " + $file)
            & $signtool sign @($cred + @("/fd", "SHA256", "/td", "SHA256", "/tr", $timestamp)) $file
            if ($LASTEXITCODE -ne 0) { throw "signtool failed on $file" }
        }
    }
    finally {
        if ($pfxPath -and (Test-Path $pfxPath)) { Remove-Item $pfxPath -Force -ErrorAction SilentlyContinue }
    }
}

# Every path that requested a signature must now verify as Valid.
Assert-Signed $targets
Write-Host "signing: complete"
