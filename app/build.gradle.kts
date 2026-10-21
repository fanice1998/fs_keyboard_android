import java.util.Properties

plugins {
    id("com.android.application")
}

val rimeEnabled = (findProperty("rime.enabled") as? String)?.toBoolean() ?: false

android {
    namespace = "com.fskeyboard.ime"
    compileSdk = 36

    val commitCount = providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        workingDir = rootProject.layout.projectDirectory.asFile
        isIgnoreExitValue = true
    }.standardOutput.asText.map { text ->
        text.trim().toIntOrNull()?.takeIf { it > 0 } ?: 1
    }.get()

    defaultConfig {
        applicationId = "com.fskeyboard.ime"
        minSdk = 26
        targetSdk = 36
        // -PversionCode overrides the commit count. A shallow clone reports too small a number.
        versionCode = (findProperty("versionCode") as? String)?.toIntOrNull() ?: commitCount
        versionName = (findProperty("versionName") as? String) ?: "0.1.$commitCount"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    val releaseKeystore = loadReleaseKeystore()

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore.store
                storePassword = releaseKeystore.storePassword
                keyAlias = releaseKeystore.alias
                keyPassword = releaseKeystore.keyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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

/** Release key material from the environment or keystore.properties. Absent means the release APK stays unsigned. */
private fun loadReleaseKeystore(): ReleaseKeystore? {
    val properties = Properties()
    val propFile = rootProject.file("keystore.properties")
    if (propFile.isFile) {
        propFile.inputStream().use { properties.load(it) }
    }
    fun setting(env: String, property: String): String? {
        return System.getenv(env)?.takeIf { it.isNotEmpty() } ?: properties.getProperty(property)?.takeIf { it.isNotEmpty() }
    }
    val storePath = setting("KEYSTORE_FILE", "storeFile") ?: return null
    val store = rootProject.file(storePath)
    if (!store.isFile) return null
    return ReleaseKeystore(
        store = store,
        storePassword = setting("KEYSTORE_PASSWORD", "storePassword"),
        alias = setting("KEY_ALIAS", "keyAlias"),
        keyPassword = setting("KEY_PASSWORD", "keyPassword"),
    )
}

private data class ReleaseKeystore(
    val store: java.io.File,
    val storePassword: String?,
    val alias: String?,
    val keyPassword: String?,
)
