plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
}

group = "dev.qutivex"
version = "0.1.0-dev"

subprojects {
    group = rootProject.group
    version = rootProject.version
}

tasks.named("check") {
    dependsOn(":core:check", ":engine:check", ":cli:check")
}

tasks.named("assemble") {
    dependsOn(":cli:assemble")
}

tasks.register<Exec>("packageWindowsMsi") {
    group = "distribution"
    description = "Packages the Qutivex CLI into a Windows x64 MSI installer"
    dependsOn(":cli:installDist")

    workingDir = rootDir
    commandLine(
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        file("scripts/package-msi.ps1").absolutePath,
        "-Version",
        project.version.toString(),
    )
}

