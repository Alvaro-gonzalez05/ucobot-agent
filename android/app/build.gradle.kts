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
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Se firma con la clave de debug a propósito: el APK se instala a mano
            // en los equipos del local, no pasa por Play Store, y una clave de
            // release obligaría a manejar un keystore en CI para nada.
            signingConfig = signingConfigs.getByName("debug")
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // OkHttp trae HTTP y WebSocket en la misma librería: el timbre y la API
    // comparten cliente y no hace falta sumar nada más. El JSON se arma con
    // org.json, que ya viene en Android.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
