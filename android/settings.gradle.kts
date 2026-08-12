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
        // iMin publica su SDK acá. Todavía no se usa (ver Printer.kt: la primera
        // versión imprime por el Bluetooth virtual, que es API estándar de
        // Android), pero queda declarado para cuando haga falta.
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "UcoBotAgent"
include(":app")
