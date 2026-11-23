pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (!providers.gradleProperty("coreOnly").isPresent) google()
        mavenCentral()
    }
}

rootProject.name = "WatchExplorer"

include(":filecore")
// The Android app needs Google's Maven repo. Pass -PcoreOnly to build/test only the file core.
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
