plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.tomlj)
    implementation(libs.kotlin.compiler.embeddable)
    implementation(platform(libs.junit.bom))
    implementation(libs.junit.platform.launcher)
    implementation(libs.junit.jupiter.engine)
    implementation(libs.kotlin.test.junit5)
}

tasks.test {
    useJUnitPlatform()
}
