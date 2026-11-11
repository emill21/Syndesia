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
        // libadb-android (wireless-debugging pairing + ADB protocol) and its SPAKE2 helper live on JitPack.
        maven("https://jitpack.io")
    }
}
rootProject.name = "Syndesia"
include(":app", ":wear")
