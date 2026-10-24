plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.eaglenet.ubuntuuml"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eaglenet.ubuntuuml"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation("org.connectbot:termlib:0.0.22")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
}
