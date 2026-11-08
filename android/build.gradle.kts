plugins {
    id("com.android.application") version "8.13.0"
    id("org.jetbrains.kotlin.android") version "2.2.20"
}

// Set by the CI workflow. Local builds fall back to a debug-like version.
val appVersionName = System.getenv("APP_VERSION_NAME") ?: "0.1.0-dev"
val appVersionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()
val keystoreFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "io.github.vrivrixd.phonon"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.vrivrixd.phonon"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // Written by the CI workflow: the native library (scripts/build-native.sh) and the
    // model pack (config, tokenizer, weights, voices) under model-assets/model.
    sourceSets["main"].jniLibs.srcDir("jniLibs")
    sourceSets["main"].assets.srcDir("model-assets")
    androidResources {
        // Stored as-is in the APK, so copying them out on first run is fast.
        noCompress += listOf("gguf", "safetensors", "json")
    }
    signingConfigs {
        create("release") {
            if (keystoreFile != null) {
                storeFile = keystoreFile
                storeType = "pkcs12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging { jniLibs { useLegacyPackaging = false } }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    testImplementation("junit:junit:4.13.2")
}
