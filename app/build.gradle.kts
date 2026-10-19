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
        versionCode = 1
        versionName = "0.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
