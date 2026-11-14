plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "br.com.strattonpay.app"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        // Versão sobe sozinha a cada build do GitHub, então o APK novo sempre instala por cima do antigo.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 2
        versionCode = build
        versionName = "1.2.$build"
    }

    // Um código, dois apps: cada marca com nome, site, cores e logos próprios.
    flavorDimensions += "marca"
    productFlavors {
        create("stratton") {
            dimension = "marca"
            applicationId = "br.com.strattonpay.app"
            resValue("string", "app_name", "Stratton")
            buildConfigField("String", "BRAND_NAME", "\"Stratton\"")
            buildConfigField("String", "BRAND_WORDMARK", "\"STRATTON\"")
            buildConfigField("String", "PANEL_URL", "\"https://app.strattonpay.com.br/\"")
            buildConfigField("String", "PANEL_DOMAIN", "\"strattonpay.com.br\"")
            buildConfigField("String", "API_BASE", "\"https://app.strattonpay.com.br/api/v1\"")
        }
        create("connect") {
            dimension = "marca"
            applicationId = "br.com.connectmdcpay.app"
            resValue("string", "app_name", "Connect Pay")
            buildConfigField("String", "BRAND_NAME", "\"Connect Pay\"")
            buildConfigField("String", "BRAND_WORDMARK", "\"CONNECT PAY\"")
            buildConfigField("String", "PANEL_URL", "\"https://app.connectmdcpay.com.br/\"")
            buildConfigField("String", "PANEL_DOMAIN", "\"connectmdcpay.com.br\"")
            buildConfigField("String", "API_BASE", "\"https://app.connectmdcpay.com.br/api/v1\"")
        }
    }

    // Mesma chave em todos os builds: sem isso o Android recusa instalar a atualização por cima.
    signingConfigs {
        // A chave vem dos segredos do GitHub (nunca fica no código).
        val keystorePath = System.getenv("STRATTON_KEYSTORE_PATH")
        if (keystorePath != null) {
            create("stratton") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("STRATTON_KEY_PASSWORD")
                keyAlias = "stratton"
                keyPassword = System.getenv("STRATTON_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Versão leve: remove código e recursos que não são usados.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Assinada com a chave da Stratton; sem ela (só para teste de compilação) usa a de debug.
            signingConfig = signingConfigs.findByName("stratton") ?: signingConfigs.getByName("debug")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
