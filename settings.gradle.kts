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
        // TinyPinyin (com.github.promeg:tinypinyin) is only published via JitPack.
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "OneAsmr"
include(":app")
