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
        // Exigido pelo LiveKit (dependência audioswitch).
        maven("https://jitpack.io") {
            content { includeGroup("com.github.davidliu") }
        }
    }
}
rootProject.name = "LunaNeuralSpace"
include(":app")
