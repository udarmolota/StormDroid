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

rootProject.name = "StormDroid"
include(":app")
// Unity's Android player (Unity 2021.3.45f2, the engine of Against the Storm) with il2mono and vkshim.
// Its Unity binaries are not in the repository: tools/unity/prepare_unity.sh extracts them from Unity's
// Android Support package into the git-ignored unity/prepared (or the folder named by the Gradle
// property / local.properties entry unityPrepared).
include(":unity")
