plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "pl.meshcore.observerwidget"
    compileSdk = 35
    defaultConfig {
        applicationId = "pl.meshcore.observerwidget"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "2.4.0-beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Stały klucz (w repo), żeby każde APK z GitHub Actions instalowało się jako aktualizacja poprzedniego.
    signingConfigs {
        create("ci") {
            storeFile = file("observer-ci.p12")
            storeType = "pkcs12"
            storePassword = "observer"
            keyAlias = "observer"
            keyPassword = "observer"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("ci") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ci")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
