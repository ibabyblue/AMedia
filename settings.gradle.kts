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

rootProject.name = "AMedia"

include(":amedia")
include(":amedia-core")
include(":amedia-compose")
include(":amedia-compose-material3")
include(":amedia-feed")
include(":amedia-cache")
include(":amedia-session")
include(":example")
