plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Bluetooth HID (mouse + keyboard) shared by the watch app and the phone app.
android {
    namespace = "com.syndesia.hid"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
