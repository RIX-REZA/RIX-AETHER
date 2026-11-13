<#
.SYNOPSIS
  Builds every Windows architecture RIX claims to support.

.DESCRIPTION
  x86, x64 and ARM64, each with its own self-contained app, tunnel helper,
  portable ZIP and installer. Aether's Windows core is published per
  architecture; point -CoreRoot at a folder with win-x86\, win-x64\ and
  win-arm64\ subfolders. Missing architectures are skipped with a printed
  reason instead of being published with a core they cannot run.

  Example:
    ./build-all.ps1 -Edition 10 -CoreRoot C:\aether
#>
param(
    [string] $Version = '1.0.7',
    [string] $CoreRoot = '',
    [ValidateSet('10', '11')]
    [string] $Edition = '10'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$targets = @(
    @{ Rid = 'win-x86'; Name = 'x86' },
    @{ Rid = 'win-x64'; Name = 'x64' },
    @{ Rid = 'win-arm64'; Name = 'ARM64' }
)

$failed = @()
foreach ($target in $targets) {
    $core = ''
    if ($CoreRoot) {
        $candidate = Join-Path $CoreRoot $target.Rid
        if (Test-Path (Join-Path $candidate 'aether.exe')) { $core = $candidate }
    }

    if ($CoreRoot -and -not $core) {
        Write-Warning "no aether.exe for $($target.Rid) under $CoreRoot; skipping $($target.Name)"
        $failed += "$($target.Rid): no core"
        continue
    }

    try {
        & (Join-Path $PSScriptRoot 'build.ps1') `
            -Runtime $target.Rid -Version $Version -Edition $Edition `
            -CoreDir $core -Installer
    } catch {
        Write-Warning "$($target.Rid) failed: $_"
        $failed += "$($target.Rid): $_"
    }
}

if ($failed.Count -gt 0) {
    Write-Warning "architecture failures:"
    $failed | ForEach-Object { Write-Warning "  $_" }
    exit 1
}
Write-Host 'all architectures built' -ForegroundColor Green
