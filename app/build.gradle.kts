import java.util.Properties

plugins {
    id("com.android.application")
}

val rimeEnabled = (findProperty("rime.enabled") as? String)?.toBoolean() ?: false

android {
    namespace = "com.fskeyboard.ime"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fskeyboard.ime"
        minSdk = 26
        targetSdk = 36
        versionCode = (findProperty("versionCode") as? String)?.toIntOrNull() ?: 1
        versionName = (findProperty("versionName") as? String) ?: "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            val envKeystoreFile = System.getenv("KEYSTORE_FILE")
            val envKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
            val envKeyAlias = System.getenv("KEY_ALIAS")
            val envKeyPassword = System.getenv("KEY_PASSWORD")

            val propFile = rootProject.file("keystore.properties")
            val properties = Properties()
            if (propFile.exists()) {
                propFile.inputStream().use { properties.load(it) }
            }

            val storeFilePath = envKeystoreFile ?: properties.getProperty("storeFile")
            val storePass = envKeystorePassword ?: properties.getProperty("storePassword")
            val alias = envKeyAlias ?: properties.getProperty("keyAlias")
            val keyPass = envKeyPassword ?: properties.getProperty("keyPassword")

            if (!storeFilePath.isNullOrEmpty() && file(storeFilePath).exists()) {
                storeFile = file(storeFilePath)
                storePassword = storePass
                keyAlias = alias
                keyPassword = keyPass
            } else {
                // Fallback to debug keystore so builds without release keys won't fail
                val debugConfig = getByName("debug")
                storeFile = debugConfig.storeFile
                storePassword = debugConfig.storePassword
                keyAlias = debugConfig.keyAlias
                keyPassword = debugConfig.keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    sourceSets.named("main") {
        jniLibs.directories.add("${rootProject.projectDir}/third_party/librime/jniLibs")
    }

    if (rimeEnabled) {
        defaultConfig {
            externalNativeBuild {
                cmake {
                    arguments += "-DRIME_ROOT=${rootProject.projectDir}/third_party/librime"
                }
            }
        }
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.31.0")
    testImplementation("junit:junit:4.13.2")
}
