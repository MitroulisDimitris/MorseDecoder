pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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

rootProject.name = "morse-decoder"

// :core is a pure JVM module — the entire DSP/decoder pipeline lives here with no
// Android dependencies, so the acceptance criteria (§6) run as plain JUnit on the
// desktop and NFR-5 determinism is enforceable in CI.
// :app (Android shell — AudioRecord, SAF, foreground service, Compose UI) is added
// at milestone 3.
include(":core")
include(":app")
