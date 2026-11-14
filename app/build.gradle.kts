plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.detetiveforense.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.detetiveforense.app"
        minSdk = 26
        targetSdk = 34
        // A cada atualização, aumente versionCode (2, 3, 4...) para instalar por cima.
        versionCode = 2
        versionName = "1.1"
    }

    signingConfigs {
        create("release") {
            // Guarde este arquivo: sem ele, as próximas versões não instalam por cima.
            storeFile = file("release.jks")
            storePassword = "1utLOFksMRIcullz2qVx"
            keyAlias = "detetive"
            keyPassword = "1utLOFksMRIcullz2qVx"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
}
