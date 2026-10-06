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
    ":app", ":core:model", ":core:studio-model", ":core:native", ":core:render", ":core:ml", ":core:data", ":core:cache", ":core:ui",
    ":feature:library", ":feature:loupe", ":feature:editor", ":feature:masking", ":feature:remove", ":feature:export", ":feature:settings",
)
