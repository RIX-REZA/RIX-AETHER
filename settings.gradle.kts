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
        // Psiphon's Android tunnel library is published from a raw GitHub
        // branch and is in no public Maven repository. Scoped to the one group
        // so every other dependency still resolves from google/mavenCentral.
        maven {
            url = uri("https://raw.github.com/Psiphon-Labs/psiphon-tunnel-core-Android-library/master")
            content { includeGroup("ca.psiphon") }
        }
    }
}

rootProject.name = "RIX"
include(":app")