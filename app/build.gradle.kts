plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "no.descartes.horsel"
    compileSdk = 35

    defaultConfig {
        applicationId = "no.descartes.horsel"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "1.2"
    }

    // Fast signeringsnøkkel, så nye versjoner kan installeres over den gamle uten å miste testene.
    signingConfigs {
        create("fast") {
            storeFile = file("horsel.jks")
            storePassword = "horselsprofil"
            keyAlias = "horsel"
            keyPassword = "horselsprofil"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("fast")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fast")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
