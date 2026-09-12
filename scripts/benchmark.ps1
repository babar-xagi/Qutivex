<#
.SYNOPSIS
    Runs comprehensive performance benchmarks for the Qutivex CLI.
.DESCRIPTION
    Measures cold/warm installation, CLI startup, tree resolution, update speed,
    no-change compilation/execution, and test runs.
#>
[CmdletBinding()]
param (
    [string]$CliBinary = "",
    [string]$OutputDir = ""
)

$ErrorActionPreference = "Stop"

$scriptDir = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
$repoRoot = (Resolve-Path "$scriptDir\..").Path

if ([string]::IsNullOrWhiteSpace($CliBinary)) {
    $CliBinary = Join-Path $repoRoot "modules\cli\build\install\qutivex\bin\qutivex.bat"
}
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $repoRoot "build\benchmark"
}

$cliPath = [System.IO.Path]::GetFullPath($CliBinary)
if (-not (Test-Path $cliPath)) {
    throw "CLI executable not found at '$cliPath'. Run 'gradlew :cli:installDist' first."
}

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null
}

$benchProject = Join-Path $OutputDir "bench-app"
if (Test-Path $benchProject) {
    Remove-Item -Recurse -Force $benchProject
}

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host "  Qutivex Performance Benchmark Suite    " -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

function Measure-CliCommand {
    param(
        [string]$Name,
        [scriptblock]$Action,
        [int]$Iterations = 3
    )

    $times = @()
    for ($i = 1; $i -le $Iterations; $i++) {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        & $Action
        $sw.Stop()
        $times += $sw.ElapsedMilliseconds
    }

    $avg = ($times | Measure-Object -Average).Average
    $min = ($times | Measure-Object -Minimum).Minimum
    $max = ($times | Measure-Object -Maximum).Maximum
    
    [PSCustomObject]@{
        Benchmark = $Name
        AvgMs     = [math]::Round($avg, 1)
        MinMs     = $min
        MaxMs     = $max
    }
}

$results = @()

# 1. CLI Startup: Version & Help
Write-Host "`n1. Measuring CLI Startup Latency..." -ForegroundColor Yellow
$results += Measure-CliCommand "Startup (--version)" { & $cliPath --version | Out-Null } -Iterations 5
$results += Measure-CliCommand "Startup (--help)" { & $cliPath --help | Out-Null } -Iterations 5

# 2. Project Initialization
Write-Host "2. Measuring Project Initialization..." -ForegroundColor Yellow
$results += Measure-CliCommand "Init (new directory)" {
    $tempInit = Join-Path $OutputDir "temp-init"
    & $cliPath init $tempInit | Out-Null
    Remove-Item -Recurse -Force $tempInit
} -Iterations 3

# Setup persistent benchmark project
& $cliPath init $benchProject | Out-Null
Set-Location $benchProject

# 3. Dependency Add
Write-Host "3. Measuring Dependency Resolution & Add..." -ForegroundColor Yellow
$results += Measure-CliCommand "Add dependency (coroutines)" {
    & $cliPath add org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2 | Out-Null
} -Iterations 1

# 4. Cold Install
Write-Host "4. Measuring Dependencies Install (Cold)..." -ForegroundColor Yellow
$results += Measure-CliCommand "Install (Cold)" {
    & $cliPath install | Out-Null
} -Iterations 1

# 5. Warm Install
Write-Host "5. Measuring Dependencies Install (Warm)..." -ForegroundColor Yellow
$results += Measure-CliCommand "Install (Warm)" {
    & $cliPath install | Out-Null
} -Iterations 3

# 6. Offline & Frozen Verification
Write-Host "6. Measuring Offline + Frozen Verification..." -ForegroundColor Yellow
$results += Measure-CliCommand "Install (--offline --frozen)" {
    & $cliPath install --offline --frozen | Out-Null
} -Iterations 5

# 7. Tree Query
Write-Host "7. Measuring Transitive Dependency Tree Render..." -ForegroundColor Yellow
$results += Measure-CliCommand "Tree (transitive graph)" {
    & $cliPath tree | Out-Null
} -Iterations 5

# 8. Dependency Update
Write-Host "8. Measuring Dependency Update..." -ForegroundColor Yellow
$results += Measure-CliCommand "Update (same version / no-op)" {
    & $cliPath update org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2 | Out-Null
} -Iterations 3

# 9. No-change Application Run
Write-Host "9. Measuring Application Run (No-change)..." -ForegroundColor Yellow
$results += Measure-CliCommand "Run (No-change)" {
    & $cliPath run | Out-Null
} -Iterations 3

# 10. No-change Build
Write-Host "10. Measuring Application Build (No-change)..." -ForegroundColor Yellow
$results += Measure-CliCommand "Build (No-change)" {
    & $cliPath build | Out-Null
} -Iterations 3

# 11. Test Execution
Write-Host "11. Measuring Application Tests..." -ForegroundColor Yellow
$results += Measure-CliCommand "Test (JUnit Platform)" {
    & $cliPath test | Out-Null
} -Iterations 3

Write-Host "`n=========================================" -ForegroundColor Cyan
Write-Host "  Benchmark Summary Results               " -ForegroundColor Cyan
Write-Host "=========================================" -ForegroundColor Cyan

$results | Format-Table -AutoSize

# Cleanup
Set-Location $PSScriptRoot
Remove-Item -Recurse -Force $benchProject -ErrorAction SilentlyContinue

return $results
