plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.dreknil.wardrivebridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dreknil.wardrivebridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.github.mik3y:usb-serial-for-android:3.7.0")
    // No API key needed (unlike Google Maps) - fetches OpenStreetMap tiles directly.
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("androidx.room:room-runtime:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")
    // Android Auto: the only way an app can put UI on the car's own screen -
    // a regular Activity never runs there. Car-host-rendered templates only
    // (PaneTemplate here), not a real layout - see CarDashboardScreen.
    implementation("androidx.car.app:app:1.4.0")
}
