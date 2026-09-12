import java.util.regex.Matcher

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    applicationName = "qutivex"
    mainClass.set("dev.qutivex.cli.MainKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.processResources {
    val applicationVersion = project.version.toString()
    inputs.property("version", applicationVersion)
    filesMatching("qutivex-version.properties") {
        expand("version" to applicationVersion)
    }
}

tasks.named<JavaExec>("run") {
    workingDir(rootProject.projectDir)
    standardInput = System.`in`
}

tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val winScript = windowsScript
        if (winScript.exists()) {
            val content = winScript.readText(Charsets.UTF_8)
            val regex = Regex("(?s)@rem Find java\\.exe.*?:execute\\r?\\n")
            val customJavaCheck = """
@rem Set active code page to UTF-8 for clean Unicode emoji output
@chcp 65001 >nul 2>&1

@rem Find java.exe and validate JDK 21 requirement
if not defined JAVA_HOME goto findJavaFromPath
set "JAVA_HOME=%JAVA_HOME:"=%"
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if exist "%JAVA_EXE%" goto validateJavaVersion

:findJavaFromPath
for %%X in (java.exe) do set "JAVA_EXE=%%~${'$'}PATH:X"
if defined JAVA_EXE if exist "%JAVA_EXE%" goto validateJavaVersion

@rem Missing Java
echo Qutivex requires JDK 21. 1>&2
echo. 1>&2
echo No compatible JDK installation was found. 1>&2
echo. 1>&2
echo Install JDK 21 and ensure either: 1>&2
echo - JAVA_HOME points to the JDK, or 1>&2
echo - java.exe is available on PATH. 1>&2
echo. 1>&2
echo Then run Qutivex again. 1>&2
"%COMSPEC%" /c exit 1

:validateJavaVersion
set "VER_TMP=%TEMP%\qutivex_ver_%RANDOM%.txt"
"%JAVA_EXE%" --version > "%VER_TMP%" 2>nul
if errorlevel 1 goto execute
set /p FIRST_LINE=<"%VER_TMP%"
del "%VER_TMP%" >nul 2>&1

for /f "tokens=2" %%v in ("%FIRST_LINE%") do set "JAVA_VER=%%v"
for /f "delims=.- tokens=1" %%m in ("%JAVA_VER%") do set "JAVA_MAJOR=%%m"

if "%JAVA_MAJOR%"=="21" goto execute

echo Detected Java: %JAVA_MAJOR% 1>&2
echo Required Java: 21 1>&2
echo. 1>&2
echo Please install JDK 21 to use this version of Qutivex. 1>&2
"%COMSPEC%" /c exit 1

:execute

""".trimIndent() + "\n"
            val updated = content.replace(regex, Matcher.quoteReplacement(customJavaCheck))
            winScript.writeText(updated, Charsets.UTF_8)
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.named<Tar>("distTar") {
    compression = Compression.GZIP
    archiveExtension.set("tar.gz")
}

