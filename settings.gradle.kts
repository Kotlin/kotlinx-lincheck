pluginManagement {
    val kotlinVersion: String by settings
    plugins {
        java
        kotlin("multiplatform") version kotlinVersion
    }

    repositories {
        mavenCentral()
        jcenter()
        gradlePluginPortal()
    }
}

rootProject.name = "lincheck"