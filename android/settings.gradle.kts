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
        maven("https://maven.nexconn.ai/repository/maven-releases/") {
            content { includeGroup("ai.nexconn.chat") }
        }
    }
}

rootProject.name = "LocalCart"
include(":app")
