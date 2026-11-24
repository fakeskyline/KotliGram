plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.kotligram"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.kotligram"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val localProperties = java.util.Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.exists()) localFile.inputStream().use { load(it) }
}
val telegramApiId = localProperties.getProperty("telegram.apiId")
    ?: System.getenv("TELEGRAM_API_ID") ?: "0"
val telegramApiHash = localProperties.getProperty("telegram.apiHash")
    ?: System.getenv("TELEGRAM_API_HASH") ?: ""

android.defaultConfig {
    buildConfigField("int", "TELEGRAM_API_ID", telegramApiId.toIntOrNull()?.toString() ?: "0")
    buildConfigField("String", "TELEGRAM_API_HASH", "\"$telegramApiHash\"")
}

dependencies {
    implementation("io.github.tdlib-android:core:0.1.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
