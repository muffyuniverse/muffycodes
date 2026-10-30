// Gradle settings for the Notes app.
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

rootProject.name = "Notes"
include(":app")
// **A library, not an app.** The browser is its own module because it is the one
// part of this project that depends on a component the phone supplies rather than
// one the APK carries: `android.webkit.WebView`. Keeping it separate means the
// app's tests never load a WebView, and swapping the engine - for GeckoView, or
// for moli if a bionic build ever appears - is a dependency change rather than a
// rewrite.
include(":browser")
