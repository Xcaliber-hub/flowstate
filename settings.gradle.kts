pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Flowstate"
include(":app")
// Windows desktop app (Compose Multiplatform for JVM). Configures on any OS; only packageMsi needs Windows.
include(":desktop")
