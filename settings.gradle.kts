pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://oss.sonatype.org/content/repositories/snapshots") // Shizuku snapshots if needed
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") // libsu, Shizuku
    }
}

rootProject.name = "TitanMapper"

// ---- App ----
include(":app")

// ---- Core modules (pure/low-level, no feature deps) ----
include(":core:model")
include(":core:common")
include(":core:database")
include(":core:data")
include(":core:ui")

// ---- Service layer (input capture / mapping / dispatch / overlay) ----
include(":service")

// ---- Feature modules (Compose UI screens) ----
include(":feature:dashboard")
include(":feature:devices")
include(":feature:mapping")
include(":feature:profiles")
include(":feature:overlay")
include(":feature:macro")
include(":feature:diagnostics")
include(":feature:settings")
