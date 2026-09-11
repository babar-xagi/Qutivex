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

tasks.test {
    useJUnitPlatform()
}
