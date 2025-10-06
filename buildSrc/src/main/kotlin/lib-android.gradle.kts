import org.gradle.api.JavaVersion

plugins {
    id("com.android.library")
    kotlin("android")
    id("kotlinx-serialization")
}

android {
    compileSdk = AndroidConfig.compileSdk

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    namespace = "eu.kanade.tachiyomi.lib.${name.replace("-", "")}"
}

versionCatalogs
    .named("libs")
    .findBundle("common")
    .ifPresent { common ->
        dependencies {
            compileOnly(common)
        }
    }
