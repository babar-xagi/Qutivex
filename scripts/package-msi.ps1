[CmdletBinding()]
param (
    [string]$Version = "",
    [string]$OutputDir = "dist"
)

$ErrorActionPreference = "Stop"

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = (Resolve-Path "$scriptDir\..").Path
Set-Location $rootDir

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  Qutivex Windows MSI Packaging Pipeline" -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

# 1. Determine version
if ([string]::IsNullOrWhiteSpace($Version)) {
    $gradleProps = Get-Content "$rootDir/build.gradle.kts" -Raw
    if ($gradleProps -match 'version\s*=\s*"([^"]+)"') {
        $Version = $matches[1]
    } else {
        $Version = "0.1.0-dev"
    }
}
Write-Host "Project Version: $Version" -ForegroundColor Green

# MSI requires major.minor.build[.revision] without suffixes like -dev
$msiVersion = $Version.Split("-")[0]
if ($msiVersion -notmatch '^\d+\.\d+\.\d+') {
    $msiVersion = "0.1.0"
}
Write-Host "MSI Product Version: $msiVersion" -ForegroundColor Green

# 2. Ensure distribution is built
$installDir = "$rootDir/modules/cli/build/install/qutivex"
if (-not (Test-Path "$installDir/bin/qutivex.bat")) {
    Write-Host "Building application distribution with Gradle..." -ForegroundColor Yellow
    & "$rootDir/gradlew.bat" :cli:installDist
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
}

# 3. Write VERSION file to installation root
$versionFile = "$installDir/VERSION"
Set-Content -Path $versionFile -Value $Version -NoNewline
Write-Host "Created VERSION file at $versionFile" -ForegroundColor Green

# 4. Locate or install WiX
$wixExe = "$env:USERPROFILE\.dotnet\tools\wix.exe"
if (-not (Test-Path $wixExe)) {
    $wixCmd = Get-Command wix -ErrorAction SilentlyContinue
    if ($wixCmd) {
        $wixExe = $wixCmd.Source
    }
}

if (-not (Test-Path $wixExe)) {
    Write-Host "WiX toolset not found. Installing WiX via dotnet tool..." -ForegroundColor Yellow
    $dotnet = "dotnet"
    if (Test-Path "$env:ProgramFiles\dotnet\dotnet.exe") {
        $dotnet = "$env:ProgramFiles\dotnet\dotnet.exe"
    }
    & $dotnet tool install --global wix
    $wixExe = "$env:USERPROFILE\.dotnet\tools\wix.exe"
    if (-not (Test-Path $wixExe)) {
        throw "Failed to locate or install WiX toolset."
    }
    & $wixExe eula accept wix7
}

Write-Host "Using WiX: $wixExe" -ForegroundColor Green

# Ensure WiX EULA is accepted
try {
    & $wixExe eula accept wix7 2>$null | Out-Null
} catch {}

# 5. Prepare output directory
$outFullPath = [System.IO.Path]::GetFullPath("$rootDir/$OutputDir")
if (-not (Test-Path $outFullPath)) {
    New-Item -ItemType Directory -Force -Path $outFullPath | Out-Null
}

$msiFile = "$outFullPath/qutivex-x64.msi"
$shaFile = "$outFullPath/qutivex-x64.msi.sha256"
$wxsFile = "$rootDir/packaging/windows/qutivex.wxs"

if (-not (Test-Path $wxsFile)) {
    throw "WiX configuration file not found at $wxsFile"
}

# 6. Build MSI
Write-Host "Building $msiFile..." -ForegroundColor Cyan
& $wixExe build -arch x64 `
    -d "SourceDir=$installDir" `
    -d "Version=$msiVersion" `
    "$wxsFile" `
    -o "$msiFile"

if ($LASTEXITCODE -ne 0 -or (-not (Test-Path $msiFile))) {
    throw "WiX build failed to create $msiFile"
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
