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
rootProject.name = "rawline"
include(
    ":app", ":core:model", ":core:native", ":core:data", ":core:cache", ":core:ui",
    ":feature:library", ":feature:loupe", ":feature:settings",
)
