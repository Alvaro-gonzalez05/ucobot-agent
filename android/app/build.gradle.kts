plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.codea.ucobot.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.codea.ucobot.agent"
        // 26 = Android 8, de 2017. Lo pide el ícono adaptativo, que permite tener
        // el logo como vector y no arrastrar cinco PNG por densidad. Ningún POSNET
        // en circulación es más viejo que eso (el Swift 2 Pro trae Android 13).
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.2.0"
    }

    val releaseKeystore = System.getenv("ANDROID_KEYSTORE_PATH")
    val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")

    signingConfigs {
        if (!releaseKeystore.isNullOrBlank()) {
            create("releaseStable") {
                storeFile = file(releaseKeystore)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Los releases públicos usan siempre la misma clave. Una clave debug
            // creada por cada runner hace que Android rechace la actualización.
            signingConfig = if (!releaseKeystore.isNullOrBlank()) {
                signingConfigs.getByName("releaseStable")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // Desde AGP 8 hay que pedirlo explícitamente: dejó de generarse solo.
        // Se usa para reportar la versión de la app en el latido y en la pantalla.
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // OkHttp trae HTTP y WebSocket en la misma librería: el timbre y la API
    // comparten cliente y no hace falta sumar nada más. El JSON se arma con
    // org.json, que ya viene en Android.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Puente seguro con la web: addWebMessageListener publica el objeto sólo en el
    // dominio de UcoBot, a diferencia de addJavascriptInterface.
    implementation("androidx.webkit:webkit:1.11.0")
}
