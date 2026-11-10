import java.io.File
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
}

android {
    namespace = "com.fskeyboard.ime"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

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
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
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

    androidResources {
        noCompress += listOf("bin", "onnx")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.31.0")
}

configurations.configureEach {
    if (name.contains("UnitTest", ignoreCase = true) && name.endsWith("RuntimeClasspath")) {
        exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
    }
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "3g"
}

// Personal packages copy data/chiakey/ChiaKeySource.db into generated assets.
// -PbundleChiaKeyLexicon=false skips that copy so CI can build without the file.
val bundleChiaKeyLexiconFlag = (
    findProperty("bundleChiaKeyLexicon") ?: rootProject.findProperty("bundleChiaKeyLexicon")
)?.toString()
val bundleChiaKeyLexicon = !bundleChiaKeyLexiconFlag.orEmpty().equals("false", ignoreCase = true)

if (bundleChiaKeyLexicon) {
    val copyChiaKeyLexicon = tasks.register<CopyChiaKeyLexiconTask>("copyChiaKeyLexicon") {
        val source = rootProject.layout.projectDirectory.file("data/chiakey/ChiaKeySource.db")
        sourceFile.set(source)
        outputDirectory.set(layout.buildDirectory.dir("generated/chiakeyAssets"))
        val disk = source.asFile
        sourcePresent.set(disk.isFile)
        if (disk.isFile) sourceStamp.set("${disk.length()}:${disk.lastModified()}")
    }
    androidComponents.onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyChiaKeyLexicon,
            CopyChiaKeyLexiconTask::outputDirectory,
        )
    }
}

// The public 靈犀 pack is local. -PbundleLingxi=false skips the copy so CI can build without it.
val bundleLingxiFlag = (
    findProperty("bundleLingxi") ?: rootProject.findProperty("bundleLingxi")
)?.toString()
val bundleLingxi = !bundleLingxiFlag.orEmpty().equals("false", ignoreCase = true)

if (bundleLingxi) {
    val copyLingxiPack = tasks.register<CopyLingxiPackTask>("copyLingxiPack") {
        val root = rootProject.layout.projectDirectory.dir("data/lingxi")
        sourceDirectory.set(root)
        outputDirectory.set(layout.buildDirectory.dir("generated/lingxiAssets"))
        val lexicon = root.file("data/lexicon.bin").asFile
        sourcePresent.set(lexicon.isFile)
        if (lexicon.isFile) sourceStamp.set("${lexicon.length()}:${lexicon.lastModified()}")
    }
    androidComponents.onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyLingxiPack,
            CopyLingxiPackTask::outputDirectory,
        )
    }
}

/** Copies the local ChiaKey database into build/generated assets. Absent file fails the package. */
abstract class CopyChiaKeyLexiconTask : DefaultTask() {
    @get:Internal
    abstract val sourceFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val sourcePresent: Property<Boolean>

    @get:Optional
    @get:Input
    abstract val sourceStamp: Property<String>

    @TaskAction
    fun copyLexicon() {
        val source = sourceFile.get().asFile
        if (!source.isFile) error("Missing data/chiakey/ChiaKeySource.db")
        val dest = outputDirectory.get().asFile.resolve("chiakey/ChiaKeySource.db")
        dest.parentFile?.mkdirs()
        val partial = File(dest.parentFile, "${dest.name}.partial")
        source.copyTo(partial, overwrite = true)
        if (!partial.renameTo(dest)) {
            partial.copyTo(dest, overwrite = true)
            partial.delete()
        }
    }
}

/** Copies the local 靈犀 dictionary and select weights into build/generated assets. */
abstract class CopyLingxiPackTask : DefaultTask() {
    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Input
    abstract val sourcePresent: Property<Boolean>

    @get:Optional
    @get:Input
    abstract val sourceStamp: Property<String>

    @TaskAction
    fun copyPack() {
        val root = sourceDirectory.get().asFile
        val files = listOf(
            "data/lexicon.bin" to "lingxi/lexicon.bin",
            "data/lm.bin" to "lingxi/lm.bin",
            "data/english/words.bin" to "lingxi/english/words.bin",
            "select/model.onnx" to "lingxi/select/model.onnx",
            "select/vocab.json" to "lingxi/select/vocab.json",
            "select/manifest.json" to "lingxi/select/manifest.json",
        )
        for ((relative, destRelative) in files) {
            val source = File(root, relative)
            if (!source.isFile) error("Missing data/lingxi/$relative")
            val dest = outputDirectory.get().asFile.resolve(destRelative)
            dest.parentFile?.mkdirs()
            val partial = File(dest.parentFile, "${dest.name}.partial")
            source.copyTo(partial, overwrite = true)
            if (!partial.renameTo(dest)) {
                partial.copyTo(dest, overwrite = true)
                partial.delete()
            }
        }
    }
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
    val store: File,
    val storePassword: String?,
    val alias: String?,
    val keyPassword: String?,
)
