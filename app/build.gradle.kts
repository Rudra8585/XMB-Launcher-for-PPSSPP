import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The release key comes from keystore.properties (local builds, git-ignored) or from environment
// variables (CI, filled from repository secrets). Without either, release builds use the debug key.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? = keystoreProps.getProperty(prop) ?: System.getenv(env)
val releaseStore = signingValue("storeFile", "XMB_KEYSTORE")?.let { file(it) }?.takeIf { it.isFile }

android {
    namespace = "com.rudra.xmb"
    compileSdk = 34
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.rudra.xmb"
        minSdk = 26
        targetSdk = 34
        // CI sets these from the run number and the release tag.
        versionCode = System.getenv("XMB_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("XMB_VERSION_NAME") ?: "1.0-dev"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86") }
        externalNativeBuild { cmake { arguments += "-DANDROID_STL=c++_static" } }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = releaseStore
                storePassword = signingValue("storePassword", "XMB_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "XMB_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "XMB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
}
