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
        // libadb-android and its certificate helper are published on JitPack.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("""com\.github\.MuntashirAkon.*""") }
        }
    }
}

rootProject.name = "PocketPortal"
include(":app")
include(":quest")
