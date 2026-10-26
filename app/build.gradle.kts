plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    ndkVersion = "29.0.14206865"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.5"
        }
    }

    namespace = "com.eaglenet.ubuntuuml"
    compileSdk = 37

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

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
    testImplementation("junit:junit:4.13.2")
}
