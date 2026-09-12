[CmdletBinding()]
param (
    [string]$Version = "",
    [string]$OutputDir = "dist"
)

$ErrorActionPreference = "Stop"

# Use $PSScriptRoot when available, fallback to invocation path
$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$rootDir = (Resolve-Path "$scriptDir\..").Path
Set-Location $rootDir

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  Qutivex Windows MSI Packaging Pipeline" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# Ensure .dotnet/tools and DOTNET_ROOT are present in session PATH
if (Test-Path "$env:USERPROFILE\.dotnet\tools") {
    $env:PATH = "$env:USERPROFILE\.dotnet\tools;$env:PATH"
}
if ($env:DOTNET_ROOT -and (Test-Path $env:DOTNET_ROOT)) {
    $env:PATH = "$env:DOTNET_ROOT;$env:PATH"
}

# 1. Determine version
if ([string]::IsNullOrWhiteSpace($Version)) {
    $gradleProps = Get-Content "$rootDir/build.gradle.kts" -Raw
    if ($gradleProps -match 'version\s*=\s*(?:providers\.gradleProperty\("qutivexVersion"\)\.getOrElse\("([^"]+)"\)|"([^"]+)")') {
        $Version = if ($matches[1]) { $matches[1] } else { $matches[2] }
    } else {
        $Version = "0.2.0-dev"
    }
}
Write-Host "Project Version: $Version" -ForegroundColor Green

# MSI requires major.minor.build[.revision] without suffixes like -dev
$msiVersion = $Version.Split("-")[0]
if ($msiVersion -notmatch '^\d+\.\d+\.\d+') {
    $msiVersion = "0.1.0"
}
Write-Host "MSI Product Version: $msiVersion" -ForegroundColor Green

# 2. Ensure distribution is built and paths are canonicalized
$installDir = [System.IO.Path]::GetFullPath("$rootDir/modules/cli/build/install/qutivex")
if (-not (Test-Path "$installDir\bin\qutivex.bat")) {
    Write-Host "Building application distribution with Gradle..." -ForegroundColor Yellow
    & "$rootDir\gradlew.bat" :cli:installDist --console=plain
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
}

# 3. Write VERSION file to installation root
$versionFile = [System.IO.Path]::GetFullPath("$installDir/VERSION")
Set-Content -Path $versionFile -Value $Version -NoNewline
Write-Host "Created VERSION file at $versionFile" -ForegroundColor Green

# 4. Locate or install WiX
$wixExe = ""
$wixCmd = Get-Command wix -ErrorAction SilentlyContinue
if ($wixCmd) {
    $wixExe = $wixCmd.Source
} elseif (Test-Path "$env:USERPROFILE\.dotnet\tools\wix.exe") {
    $wixExe = "$env:USERPROFILE\.dotnet\tools\wix.exe"
}

if (-not $wixExe -or -not (Test-Path $wixExe)) {
    Write-Host "WiX toolset not found on PATH. Installing WiX via dotnet tool..." -ForegroundColor Yellow
    $dotnet = "dotnet"
    if ($env:DOTNET_ROOT -and (Test-Path "$env:DOTNET_ROOT\dotnet.exe")) {
        $dotnet = "$env:DOTNET_ROOT\dotnet.exe"
    } elseif (Test-Path "$env:ProgramFiles\dotnet\dotnet.exe") {
        $dotnet = "$env:ProgramFiles\dotnet\dotnet.exe"
    }
    & $dotnet tool install --global wix
    if (Test-Path "$env:USERPROFILE\.dotnet\tools\wix.exe") {
        $wixExe = "$env:USERPROFILE\.dotnet\tools\wix.exe"
    }
}

if (-not $wixExe -or -not (Test-Path $wixExe)) {
    throw "Failed to locate or install WiX toolset (wix.exe)."
}

Write-Host "Using WiX: $wixExe" -ForegroundColor Green

# Ensure WiX EULA is accepted if required by the installed WiX version
try {
    & $wixExe eula accept wix7 2>&1 | Out-Null
} catch {}

# 5. Prepare output directory with canonical paths
$outFullPath = [System.IO.Path]::GetFullPath("$rootDir/$OutputDir")
if (-not (Test-Path $outFullPath)) {
    New-Item -ItemType Directory -Force -Path $outFullPath | Out-Null
}

$msiFile = [System.IO.Path]::GetFullPath("$outFullPath/qutivex-x64.msi")
$shaFile = [System.IO.Path]::GetFullPath("$outFullPath/qutivex-x64.msi.sha256")
$wxsFile = [System.IO.Path]::GetFullPath("$rootDir/packaging/windows/qutivex.wxs")

if (-not (Test-Path $wxsFile)) {
    throw "WiX configuration file not found at $wxsFile"
}

# 6. Build MSI
Write-Host "Building $msiFile from $wxsFile..." -ForegroundColor Cyan
Write-Host "SourceDir: $installDir" -ForegroundColor Gray
Write-Host "Version: $msiVersion" -ForegroundColor Gray

& $wixExe build -arch x64 `
    -d "SourceDir=$installDir" `
    -d "Version=$msiVersion" `
    "$wxsFile" `
    -o "$msiFile"

if ($LASTEXITCODE -ne 0 -or (-not (Test-Path $msiFile))) {
    throw "WiX build failed with exit code $LASTEXITCODE to create $msiFile"
}

$fileInfo = Get-Item $msiFile
$fileSizeKb = [math]::Round($fileInfo.Length / 1KB, 2)
Write-Host "Successfully generated: $msiFile ($fileSizeKb KB)" -ForegroundColor Green

# 7. Compute SHA-256 Checksum
$hash = (Get-FileHash -Path $msiFile -Algorithm SHA256).Hash.ToLowerInvariant()
$shaContent = "$hash  qutivex-x64.msi`n"
[System.IO.File]::WriteAllText($shaFile, $shaContent, [System.Text.Encoding]::UTF8)

Write-Host "SHA-256 Digest: $hash" -ForegroundColor Yellow
Write-Host "Wrote checksum to: $shaFile" -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  Packaging Complete!" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan
