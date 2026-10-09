plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "no.molle.intervaller"
    compileSdk = 34

    defaultConfig {
        applicationId = "no.molle.intervaller"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Fast nøkkel i prosjektet, slik at nye versjoner kan installeres over de gamle
    // uansett hvor appen bygges (egen PC eller GitHub).
    signingConfigs {
        create("molle") {
            storeFile = file("molle.keystore")
            storePassword = "molle-intervaller"
            keyAlias = "molle"
            keyPassword = "molle-intervaller"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("molle")
        }
        debug {
            signingConfig = signingConfigs.getByName("molle")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
