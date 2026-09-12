<#
.SYNOPSIS
    Installs the Qutivex CLI on Windows.
.DESCRIPTION
    Downloads and installs the latest or specified version of Qutivex to %LOCALAPPDATA%\Qutivex.
    Adds %LOCALAPPDATA%\Qutivex\bin to the user's PATH environment variable.
.EXAMPLE
    irm https://raw.githubusercontent.com/babar-xagi/Qutivex/main/scripts/install.ps1 | iex
#>
[CmdletBinding()]
param (
    [string]$Version = "0.2.2",
    [string]$InstallDir = "$env:LOCALAPPDATA\Qutivex"
)

$ErrorActionPreference = "Stop"

Write-Host "✨ Installing Qutivex v$Version on Windows..." -ForegroundColor Cyan

# Check Java runtime (JDK 21+ required)
try {
    $javaVerOutput = java -version 2>&1 | Out-String
    if ($javaVerOutput -match 'version "(\d+)') {
        $javaMajor = [int]$matches[1]
        if ($javaMajor -lt 21) {
            Write-Warning "Detected Java major version $javaMajor. Qutivex requires JDK 21 or higher."
        }
    }
} catch {
    Write-Warning "Java 21+ was not found on PATH. Please ensure JDK 21 is installed."
}

$zipUrl = "https://github.com/babar-xagi/Qutivex/releases/download/v$Version/qutivex-$Version.zip"
$tempZip = Join-Path ([System.IO.Path]::GetTempPath()) "qutivex-$Version.zip"

Write-Host "📥 Downloading Qutivex archive from GitHub Releases..." -ForegroundColor Cyan
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Invoke-WebRequest -Uri $zipUrl -OutFile $tempZip -UseBasicParsing

Write-Host "📦 Unpacking to $InstallDir..." -ForegroundColor Cyan
if (Test-Path $InstallDir) {
    Remove-Item -Recurse -Force $InstallDir
}
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null

$tempExtract = Join-Path ([System.IO.Path]::GetTempPath()) "qutivex-extract-$Version"
if (Test-Path $tempExtract) { Remove-Item -Recurse -Force $tempExtract }
Expand-Archive -Path $tempZip -DestinationPath $tempExtract -Force

# Locate unzipped root directory
$extractedRoot = Get-ChildItem -Path $tempExtract -Directory | Select-Object -First 1
if ($extractedRoot) {
    Copy-Item -Path "$($extractedRoot.FullName)\*" -Destination $InstallDir -Recurse -Force
} else {
    Copy-Item -Path "$tempExtract\*" -Destination $InstallDir -Recurse -Force
}

Remove-Item -Recurse -Force $tempExtract -ErrorAction SilentlyContinue
Remove-Item -Force $tempZip -ErrorAction SilentlyContinue

$binDir = Join-Path $InstallDir "bin"

# Add to user PATH if not present
$userPath = [Environment]::GetEnvironmentVariable("PATH", [EnvironmentVariableTarget]::User)
if ($userPath -notlike "*$binDir*") {
    $newPath = "$binDir;$userPath"
    [Environment]::SetEnvironmentVariable("PATH", $newPath, [EnvironmentVariableTarget]::User)
    $env:PATH = "$binDir;$env:PATH"
    Write-Host "Added $binDir to user PATH." -ForegroundColor Green
}

Write-Host "✅ Qutivex v$Version installed successfully!" -ForegroundColor Green
Write-Host "Binary location: $binDir\qutivex.bat" -ForegroundColor Gray
Write-Host "Run 'qutivex --help' to get started." -ForegroundColor Cyan
