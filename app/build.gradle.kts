plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.rafajames.controletv"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.rafajames.controletv"
        minSdk = 26
        targetSdk = 34
        versionCode = build
        versionName = "1.0.$build"
    }
    // Chave fixa para o app atualizar sem precisar desinstalar
    signingConfigs { getByName("debug") { storeFile = file("debug.keystore") } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { implementation("com.squareup.okhttp3:okhttp:4.12.0") }
