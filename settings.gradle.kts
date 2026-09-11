plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "qutivex"

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

include(":core", ":engine", ":cli")
project(":core").projectDir = file("modules/core")
project(":engine").projectDir = file("modules/engine")
project(":cli").projectDir = file("modules/cli")
