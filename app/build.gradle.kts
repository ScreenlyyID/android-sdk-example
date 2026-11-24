plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.screenlyyid.democamera"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.screenlyyid.democamera"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// No third-party libraries: the example uses only the Android framework (WebView, HttpURLConnection, org.json),
// so there is nothing to keep up to date and nothing to copy that you do not need.
