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
