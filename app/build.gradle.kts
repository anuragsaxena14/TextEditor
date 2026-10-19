plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.anurag.simpleeditor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anurag.simpleeditor"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"
    }

    buildTypes {
        release {
            // Release build = not debuggable. Signed with the build machine's
            // debug key so no keystore needs to be stored in the repo.
            isMinifyEnabled = false
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
}
// No extra libraries: the app uses only the Android framework.
