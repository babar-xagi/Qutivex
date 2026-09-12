param([switch]$SkipPerformance)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

$Results = New-Object System.Collections.ArrayList
$Perf = [ordered]@{}
$Repo = (Get-Location).Path

function Add-Result {
    param([string]$Name,[ValidateSet("PASS","FAIL","WARN","SKIP")][string]$Status,[string]$Details="")
    [void]$Results.Add([pscustomobject]@{Name=$Name;Status=$Status;Details=$Details})
    $c = switch($Status){"PASS"{"Green"}"FAIL"{"Red"}"WARN"{"Yellow"}default{"DarkYellow"}}
    Write-Host "[$Status] $Name" -ForegroundColor $c
    if($Details){Write-Host "       $Details" -ForegroundColor $c}
}
function Check([bool]$Ok,[string]$Name,[string]$Details=""){
    if($Ok){Add-Result $Name "PASS"; return $true}
    Add-Result $Name "FAIL" $Details; return $false
}
function Invoke-Gradle([string[]]$GradleArgs,[switch]$Quiet){
    $old=Get-Location
    try{
        Set-Location $Repo
        $o=(& (Join-Path $Repo "gradlew.bat") @GradleArgs 2>&1|Out-String)
        $c=$LASTEXITCODE;if($null-eq$c){$c=0}
        if(-not$Quiet -and $o.Trim()){Write-Host $o.TrimEnd()}
        [pscustomobject]@{ExitCode=[int]$c;Output=$o}
    }catch{[pscustomobject]@{ExitCode=998;Output=$_.Exception.ToString()}}
    finally{Set-Location $old}
}
function Invoke-Qx([string[]]$QxArgs,[string]$Dir=""){
    $old=Get-Location
    try{
        if($Dir){Set-Location $Dir}
        $o=(& $script:Qx @QxArgs 2>&1|Out-String)
        $c=$LASTEXITCODE;if($null-eq$c){$c=0}
        if($o.Trim()){Write-Host $o.TrimEnd()}
        [pscustomobject]@{ExitCode=[int]$c;Output=$o}
    }catch{[pscustomobject]@{ExitCode=998;Output=$_.Exception.ToString()}}
    finally{Set-Location $old}
}
function Find-Qx{
    $f=@(Get-ChildItem $Repo -Recurse -File -Filter qutivex.bat -ErrorAction SilentlyContinue |
        Where-Object {$_.FullName -match '\\build\\install\\qutivex\\bin\\qutivex\.bat$'} |
        Select-Object -ExpandProperty FullName)
    if($f.Count){$f[0]}else{$null}
}
function Write-U8([string]$Path,[string]$Text){
    New-Item -ItemType Directory -Force -Path ([IO.Path]::GetDirectoryName($Path))|Out-Null
    [IO.File]::WriteAllText($Path,$Text,[Text.UTF8Encoding]::new($false))
}
function Find-Jar([string]$Project){
    $d=Join-Path $Project "build\libs"
    if(-not(Test-Path $d)){return $null}
    $j=@(Get-ChildItem $d -File -Filter *.jar|Sort-Object LastWriteTime -Descending)
    if($j.Count){$j[0].FullName}else{$null}
}
function Measure-Qx([string]$Name,[string[]]$QxArgs,[string]$Dir){
    $sw=[Diagnostics.Stopwatch]::StartNew();$r=Invoke-Qx $QxArgs $Dir;$sw.Stop()
    $Perf[$Name]=[math]::Round($sw.Elapsed.TotalSeconds,3)
    Write-Host "[TIME] $Name = $($Perf[$Name])s" -ForegroundColor Magenta
    $r
}

if(-not(Test-Path (Join-Path $Repo "gradlew.bat"))){
    Write-Host "Run this from Qutivex repo root." -ForegroundColor Red; exit 1
}

$stamp=Get-Date -Format "yyyyMMdd-HHmmss"
$out=Join-Path $Repo "verification-results\phase4\$stamp"
New-Item -ItemType Directory -Force -Path $out|Out-Null
$summary=Join-Path $out "summary.txt"
$transcript=Join-Path $out "transcript.txt"
$json=Join-Path $out "results.json"
try{Start-Transcript $transcript -Force|Out-Null}catch{}

Write-Host "QUTIVEX PHASE 4 REPOSITORY VERIFICATION" -ForegroundColor Cyan
Write-Host "Repo: $Repo"
Write-Host "Results: $out"

Write-Host "`n=== 1. Source tests ===" -ForegroundColor Cyan
$r=Invoke-Gradle @("test");Check ($r.ExitCode-eq0) "gradlew test" $r.Output|Out-Null
$r=Invoke-Gradle @("check");Check ($r.ExitCode-eq0) "gradlew check" $r.Output|Out-Null

$focused=$false
foreach($a in @(
    @(":cli:test","--tests","*Phase4IntegrationTest*"),
    @(":modules:cli:test","--tests","*Phase4IntegrationTest*"),
    @("test","--tests","*Phase4IntegrationTest*")
)){
    $r=Invoke-Gradle $a -Quiet
    if($r.ExitCode-eq0){$focused=$true;break}
}
if($focused){Add-Result "Focused Phase4IntegrationTest" "PASS"}
else{Add-Result "Focused Phase4IntegrationTest" "WARN" "Exact test task/class path not found."}

Write-Host "`n=== 2. Build local CLI ===" -ForegroundColor Cyan
$built=$false
foreach($task in @(@(":cli:installDist"),@(":modules:cli:installDist"),@("installDist"))){
    $r=Invoke-Gradle $task -Quiet
    if($r.ExitCode-eq0){
        $script:Qx=Find-Qx
        if($script:Qx){$built=$true;break}
    }
}
if(-not$built){Add-Result "Build local CLI" "FAIL" "installDist output not found";throw "Cannot continue"}
Add-Result "Build local CLI" "PASS" $script:Qx

$r=Invoke-Qx @("--version")
Check (($r.ExitCode-eq0)-and($r.Output-match'0\.4\.0')) "Local CLI reports 0.4.0" $r.Output|Out-Null

Write-Host "`n=== 3. Fresh project ===" -ForegroundColor Cyan
$ws=Join-Path $out "workspace";New-Item -ItemType Directory -Force -Path $ws|Out-Null
$r=Invoke-Qx @("init","phase4-app") $ws
if(-not(Check ($r.ExitCode-eq0) "qutivex init" $r.Output)){throw "init failed"}
$project=Join-Path $ws "phase4-app"

$r=Invoke-Qx @("add","org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") $project
Check ($r.ExitCode-eq0) "Add coroutines" $r.Output|Out-Null
$r=Invoke-Qx @("add","--test","org.junit.jupiter:junit-jupiter:5.12.2") $project
Check ($r.ExitCode-eq0) "Add JUnit" $r.Output|Out-Null

$main=Join-Path $project "src\main\kotlin\Main.kt"
$test=Join-Path $project "src\test\kotlin\SampleTest.kt"
$res=Join-Path $project "src\main\resources\phase4-resource.txt"

Write-U8 $main @'
import kotlinx.coroutines.runBlocking
fun main(args: Array<String>) = runBlocking {
    val r = object {}.javaClass.getResource("/phase4-resource.txt")?.readText()?.trim() ?: "missing"
    println("PHASE4_NATIVE_OK:" + args.joinToString("|"))
    println("RESOURCE:" + r)
}
'@
Write-U8 $test @'
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
class SampleTest {
    @Test fun additionWorks() { assertEquals(4, 2 + 2) }
}
'@
Write-U8 $res "resource-ok"

Write-Host "`n=== 4. Poison Gradle wrapper ===" -ForegroundColor Cyan
$wrappers=@()
foreach($p in @((Join-Path $project ".qutivex\gradle\gradlew.bat"),(Join-Path $project ".qutivex\gradle\gradlew"))){
    if(Test-Path $p){
        $b="$p.phase4bak";Copy-Item $p $b -Force
        $wrappers += [pscustomobject]@{P=$p;B=$b}
        if($p-like"*.bat"){Write-U8 $p "@echo off`r`necho GRADLE_WAS_CALLED_WHEN_IT_SHOULD_NOT_BE 1>&2`r`nexit /b 99"}
        else{Write-U8 $p "#!/bin/sh`necho GRADLE_WAS_CALLED_WHEN_IT_SHOULD_NOT_BE >&2`nexit 99"}
    }
}
if($wrappers.Count){Add-Result "Poison project Gradle wrapper" "PASS"}else{Add-Result "Poison project Gradle wrapper" "WARN" "No wrapper found"}

try{
    $r=Invoke-Qx @("run","--","first","second with space") $project
    Check (($r.ExitCode-eq0)-and(-not($r.Output-match'GRADLE_WAS_CALLED'))-and($r.Output-match'PHASE4_NATIVE_OK:first\|second with space')) "Native run + args, zero Gradle" $r.Output|Out-Null
    Check ($r.Output-match'RESOURCE:resource-ok') "Native run resources" $r.Output|Out-Null

    $r=Invoke-Qx @("test") $project
    Check (($r.ExitCode-eq0)-and(-not($r.Output-match'GRADLE_WAS_CALLED'))-and($r.Output-match'PASSED|Tests passed')) "Native JUnit tests, zero Gradle" $r.Output|Out-Null

    $r=Invoke-Qx @("build") $project
    Check (($r.ExitCode -eq 0) -and (-not ($r.Output -match 'GRADLE_WAS_CALLED'))) "Native build, zero Gradle" $r.Output | Out-Null
}finally{
    foreach($w in $wrappers){if(Test-Path $w.B){Move-Item $w.B $w.P -Force}}
}

Write-Host "`n=== 5. Runnable JAR ===" -ForegroundColor Cyan
$jar=Find-Jar $project
if($jar){
    Add-Result "Runnable JAR generated" "PASS" $jar
    $o=(& java -jar $jar jarArg 2>&1|Out-String);$c=$LASTEXITCODE
    Check (($c-eq0)-and($o-match'PHASE4_NATIVE_OK:jarArg')) "java -jar works" $o|Out-Null
    Check ($o-match'RESOURCE:resource-ok') "Packaged JAR includes resources" $o|Out-Null
}else{Add-Result "Runnable JAR generated" "FAIL" "No JAR in build/libs"}

Write-Host "`n=== 6. Incremental behavior ===" -ForegroundColor Cyan
$r1=Invoke-Qx @("build") $project
$r2=Invoke-Qx @("build") $project
Check (($r1.ExitCode-eq0)-and($r2.ExitCode-eq0)) "Repeated builds succeed" ($r1.Output+$r2.Output)|Out-Null
if($r2.Output-match'UP-TO-DATE|up.to.date|unchanged|skipp'){Add-Result "Warm build reports reuse" "PASS"}
else{Add-Result "Warm build reports reuse" "WARN" "No explicit UP-TO-DATE/reuse message."}

$orig=Get-Content $main -Raw
Write-U8 $main ($orig.Replace("PHASE4_NATIVE_OK","PHASE4_NATIVE_CHANGED"))
$r=Invoke-Qx @("build") $project
Check ($r.ExitCode-eq0) "Source change rebuilds" $r.Output|Out-Null
$jar=Find-Jar $project
$o=(& java -jar $jar changed 2>&1|Out-String);$c=$LASTEXITCODE
Check (($c-eq0)-and($o-match'PHASE4_NATIVE_CHANGED:changed')) "Rebuilt JAR reflects source change" $o|Out-Null
Write-U8 $main $orig
Invoke-Qx @("build") $project|Out-Null

Write-Host "`n=== 7. Test failure handling ===" -ForegroundColor Cyan
$passTest=Get-Content $test -Raw
Write-U8 $test ($passTest.Replace("assertEquals(4, 2 + 2)","assertEquals(5, 2 + 2)"))
$r=Invoke-Qx @("test") $project
Check (($r.ExitCode-ne0)-and($r.Output-match'FAILED|failed|Assertion')) "Failing test returns non-zero" $r.Output|Out-Null
Write-U8 $test $passTest
$r=Invoke-Qx @("test") $project
Check ($r.ExitCode-eq0) "Tests recover after restore" $r.Output|Out-Null

Write-Host "`n=== 8. Compiler diagnostics ===" -ForegroundColor Cyan
$good=Get-Content $main -Raw
Write-U8 $main ($good+"`nthis is invalid kotlin`n")
$r=Invoke-Qx @("build") $project
Check (($r.ExitCode-ne0)-and($r.Output-match'error|expecting|unresolved|compiler')) "Invalid Kotlin returns compiler error" $r.Output|Out-Null
Write-U8 $main $good
$r=Invoke-Qx @("build") $project
Check ($r.ExitCode-eq0) "Build recovers after compiler error" $r.Output|Out-Null

Write-Host "`n=== 9. Phase 3.5 regression ===" -ForegroundColor Cyan
foreach($a in @(@("list"),@("tree"),@("install","--frozen"),@("install","--offline","--frozen"))){
    $r=Invoke-Qx $a $project
    Check ($r.ExitCode-eq0) ("Regression: qutivex "+($a-join" ")) $r.Output|Out-Null
}

Write-Host "`n=== 10. Performance ===" -ForegroundColor Cyan
if($SkipPerformance){Add-Result "Performance subset" "SKIP"}
else{
    $bd=Join-Path $project "build";if(Test-Path $bd){Remove-Item $bd -Recurse -Force}
    $p1=Measure-Qx "build-cold" @("build") $project
    $p2=Measure-Qx "build-warm" @("build") $project
    $p3=Measure-Qx "run-warm" @("run") $project
    $p4=Measure-Qx "test-warm" @("test") $project
    Check (($p1.ExitCode-eq0)-and($p2.ExitCode-eq0)-and($p3.ExitCode-eq0)-and($p4.ExitCode-eq0)) "Performance subset" "One or more perf commands failed"|Out-Null
}

Write-Host "`n=== FINAL REPORT ===" -ForegroundColor Cyan
$pass=@($Results|Where-Object Status -eq "PASS").Count
$fail=@($Results|Where-Object Status -eq "FAIL").Count
$warn=@($Results|Where-Object Status -eq "WARN").Count
$skip=@($Results|Where-Object Status -eq "SKIP").Count
$verdict = if ($fail -gt 0) {"FAIL"} elseif (($warn -gt 0) -or ($skip -gt 0)) {"PARTIAL"} else {"PASS"}
Write-Host "PASS: $pass" -ForegroundColor Green
Write-Host "FAIL: $fail" -ForegroundColor Red
Write-Host "WARN: $warn" -ForegroundColor Yellow
Write-Host "SKIP: $skip" -ForegroundColor DarkYellow
Write-Host "FINAL VERDICT: $verdict" -ForegroundColor $(if($verdict-eq"PASS"){"Green"}elseif($verdict-eq"FAIL"){"Red"}else{"Yellow"})

$lines=New-Object System.Collections.Generic.List[string]
$lines.Add("Qutivex Phase 4 Repository Verification")
$lines.Add("Generated: $(Get-Date -Format o)")
$lines.Add("Repository: $Repo")
$lines.Add("Local CLI: $script:Qx")
$lines.Add("Project: $project")
$lines.Add("")
$lines.Add("RESULTS");$lines.Add("=======")
foreach($x in $Results){$lines.Add("[$($x.Status)] $($x.Name)");if($x.Details){$lines.Add("    "+($x.Details-replace"`r?`n"," | "))}}
$lines.Add("");$lines.Add("PERFORMANCE");$lines.Add("===========")
foreach($k in $Perf.Keys){$lines.Add("$k = $($Perf[$k]) s")}
$lines.Add("");$lines.Add("TOTALS");$lines.Add("======")
$lines.Add("PASS = $pass");$lines.Add("FAIL = $fail");$lines.Add("WARN = $warn");$lines.Add("SKIP = $skip");$lines.Add("");$lines.Add("FINAL VERDICT = $verdict")

[IO.File]::WriteAllLines($summary,$lines,[Text.UTF8Encoding]::new($false))
$Results|ConvertTo-Json -Depth 5|Set-Content $json -Encoding UTF8
try{Stop-Transcript|Out-Null}catch{}

Write-Host ""
Write-Host "Summary: $summary" -ForegroundColor Cyan
Write-Host "Transcript: $transcript"
Write-Host "JSON: $json"
Write-Host "Send summary.txt; if FAIL/WARN, also send transcript.txt." -ForegroundColor Cyan

if($fail){exit 1}else{exit 0}
