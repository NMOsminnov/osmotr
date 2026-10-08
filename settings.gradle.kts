// «Осмотр» — фотофиксация осмотров оборудования (docs/ТЗ.md).
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
rootProject.name = "osmotr"
include(":app")
// Общее для Android и iPhone: описи, Excel, поиск, интерфейс (Kotlin Multiplatform + Compose Multiplatform).
include(":shared")
