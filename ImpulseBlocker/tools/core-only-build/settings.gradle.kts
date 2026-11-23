// Standalone build of the pure-Kotlin :core module. It needs only Maven Central
// (no Android SDK / Google Maven), so the logic tests can run anywhere:
//   ./gradlew -p tools/core-only-build :core:test
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}
rootProject.name = "ImpulseBlockerCore"
include(":core")
project(":core").projectDir = file("../../core")
