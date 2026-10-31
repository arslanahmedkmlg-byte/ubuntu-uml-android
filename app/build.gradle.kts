plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val generatedUmlAssets = layout.buildDirectory.dir("generated/umlAssets")

abstract class GenerateUmlKernelManifest : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifestFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val source = manifestFile.get().asFile
        require(source.isFile) {
            "UML kernel artifact manifest is missing: $source"
        }
        val destination = outputDirectory.file("uml-kernel-manifest.json").get().asFile
        destination.parentFile.mkdirs()
        source.copyTo(destination, overwrite = true)
    }
}

val umlManifestTask = tasks.register<GenerateUmlKernelManifest>("generateUmlKernelManifest") {
    manifestFile.set(
        providers.environmentVariable("UML_ARTIFACT_MANIFEST")
            .map { rootProject.layout.projectDirectory.file(it) }
            .orElse(rootProject.layout.projectDirectory.file("kernel/config/baseline-artifacts.json"))
    )
    outputDirectory.set(layout.buildDirectory.dir("generated/umlAssets"))
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
    sourceSets.getByName("main").assets.directories.add(generatedUmlAssets.get().asFile.path)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    packaging { jniLibs { useLegacyPackaging = true } }
}

tasks.named("preBuild").configure {
    dependsOn(umlManifestTask)
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
