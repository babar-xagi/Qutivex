plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
}

group = "dev.qutivex"
version = providers.environmentVariable("QUTIVEX_VERSION")
    .orElse(providers.gradleProperty("qutivexVersion"))
    .getOrElse("0.4.0")

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
    standardOutput = System.out
    errorOutput = System.err

    val packageVersion = project.findProperty("qutivexVersion") as? String
        ?: project.findProperty("version") as? String
        ?: System.getenv("QUTIVEX_VERSION")
        ?: project.version.toString()

    commandLine(
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        file("scripts/package-msi.ps1").absolutePath,
        "-Version",
        packageVersion,
    )
}

tasks.register<Copy>("packageDistributionArchives") {
    group = "distribution"
    description = "Copies distZip and distTar distribution archives into dist/"
    dependsOn(":cli:distZip", ":cli:distTar")
    from(project(":cli").layout.buildDirectory.dir("distributions"))
    into(layout.projectDirectory.dir("dist"))
}

