import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val versionProps = Properties().apply {
    load(rootProject.file("version.properties").inputStream())
}
// GitHub Actions의 -P 플래그 우선, 없으면 파일 값
val appVersionName = project.findProperty("versionName")?.toString()
    ?: versionProps.getProperty("VERSION_NAME", "0.001")
val appVersionCode = project.findProperty("versionCode")?.toString()?.toIntOrNull()
    ?: versionProps.getProperty("VERSION_CODE", "1").toInt()

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.core:core-ktx:1.12.0")
}

android {
    namespace = "com.mdkdw1.myinternet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mdkdw1.myinternet"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
