import java.io.File
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

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

// The public 靈犀 pack is local. -PbundleLingxi=false skips the copy so CI can build without it.
val bundleLingxiFlag = (
    findProperty("bundleLingxi") ?: rootProject.findProperty("bundleLingxi")
)?.toString()
val bundleLingxi = !bundleLingxiFlag.orEmpty().equals("false", ignoreCase = true)

val chiaKeyDatabase = rootProject.layout.projectDirectory.file("data/chiakey/ChiaKeySource.db")
val lingxiLexiconFile = rootProject.layout.projectDirectory.file("data/lingxi/data/lexicon.bin")
val mergeLingxiWithChiaKey = bundleLingxi && bundleChiaKeyLexicon &&
    chiaKeyDatabase.asFile.isFile && lingxiLexiconFile.asFile.isFile

// A merged lexicon replaces the packaged database. The source file on disk stays.
val mergeLingxiChiaKey = if (mergeLingxiWithChiaKey) {
    tasks.register<MergeLingxiChiaKeyTask>("mergeLingxiChiaKey") {
        lexicon.set(lingxiLexiconFile)
        database.set(chiaKeyDatabase)
        mergedLexicon.set(layout.buildDirectory.file("generated/lingxiMerged/lingxi/lexicon.bin"))
        marker.set(layout.buildDirectory.file("generated/lingxiMerged/lingxi/lexicon.merged"))
        classpath.from(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"))
        classpath.from(configurations.named("debugCompileClasspath"))
        algorithmFiles.from(
            layout.projectDirectory.file("src/main/java/com/fskeyboard/ime/lingxi/LingxiLexiconMerge.kt"),
            layout.projectDirectory.file("src/main/java/com/fskeyboard/ime/lingxi/LingxiFormat.kt"),
            layout.projectDirectory.file("src/main/java/com/fskeyboard/ime/candidate/BopomofoOrder.kt"),
        )
        dependsOn("compileDebugKotlin")
    }
} else {
    null
}

if (bundleChiaKeyLexicon && mergeLingxiChiaKey == null) {
    val copyChiaKeyLexicon = tasks.register<CopyChiaKeyLexiconTask>("copyChiaKeyLexicon") {
        sourceFile.set(chiaKeyDatabase)
        outputDirectory.set(layout.buildDirectory.dir("generated/chiakeyAssets"))
        val disk = chiaKeyDatabase.asFile
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

if (bundleLingxi) {
    val copyLingxiPack = tasks.register<CopyLingxiPackTask>("copyLingxiPack") {
        val root = rootProject.layout.projectDirectory.dir("data/lingxi")
        sourceDirectory.set(root)
        outputDirectory.set(layout.buildDirectory.dir("generated/lingxiAssets"))
        val lexicon = root.file("data/lexicon.bin").asFile
        sourcePresent.set(lexicon.isFile)
        if (lexicon.isFile) sourceStamp.set("${lexicon.length()}:${lexicon.lastModified()}")
        if (mergeLingxiChiaKey != null) {
            dependsOn(mergeLingxiChiaKey)
            mergedLexicon.set(mergeLingxiChiaKey.flatMap { it.mergedLexicon })
            mergedMarker.set(mergeLingxiChiaKey.flatMap { it.marker })
        }
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

    @get:Optional
    @get:InputFile
    abstract val mergedLexicon: RegularFileProperty

    @get:Optional
    @get:InputFile
    abstract val mergedMarker: RegularFileProperty

    @TaskAction
    fun copyPack() {
        val root = sourceDirectory.get().asFile
        val merged = mergedLexicon.orNull?.asFile?.takeIf { it.isFile }
        val files = listOf(
            "data/lexicon.bin" to "lingxi/lexicon.bin",
            "data/lm.bin" to "lingxi/lm.bin",
            "data/english/words.bin" to "lingxi/english/words.bin",
            "select/model.onnx" to "lingxi/select/model.onnx",
            "select/vocab.json" to "lingxi/select/vocab.json",
            "select/manifest.json" to "lingxi/select/manifest.json",
        )
        for ((relative, destRelative) in files) {
            val source = if (relative == "data/lexicon.bin" && merged != null) {
                merged
            } else {
                File(root, relative)
            }
            if (!source.isFile) error("Missing data/lingxi/$relative")
            val dest = outputDirectory.get().asFile.resolve(destRelative)
            copyAtomically(source, dest)
        }
        val markerDest = outputDirectory.get().asFile.resolve("lingxi/lexicon.merged")
        val marker = mergedMarker.orNull?.asFile?.takeIf { it.isFile }
        if (merged != null && marker != null) {
            copyAtomically(marker, markerDest)
        } else {
            markerDest.delete()
        }
    }

    private fun copyAtomically(source: File, dest: File) {
        dest.parentFile?.mkdirs()
        val partial = File(dest.parentFile, "${dest.name}.partial")
        source.copyTo(partial, overwrite = true)
        if (!partial.renameTo(dest)) {
            partial.copyTo(dest, overwrite = true)
            partial.delete()
        }
    }
}

/**
 * Writes build/generated/lingxiMerged/lingxi/lexicon.bin and lexicon.merged.
 * Does not replace data/lingxi/data/lexicon.bin.
 */
abstract class MergeLingxiChiaKeyTask : DefaultTask() {
    @get:InputFile
    abstract val lexicon: RegularFileProperty

    @get:InputFile
    abstract val database: RegularFileProperty

    @get:OutputFile
    abstract val mergedLexicon: RegularFileProperty

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @get:Internal
    abstract val classpath: ConfigurableFileCollection

    @get:InputFiles
    abstract val algorithmFiles: ConfigurableFileCollection

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun merge() {
        val rows = File(temporaryDir, "unigrams.rows")
        val db = database.get().asFile
        val process = try {
            ProcessBuilder("sqlite3", "-readonly", db.absolutePath)
                .redirectOutput(ProcessBuilder.Redirect.to(rows))
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        } catch (_: java.io.IOException) {
            error("sqlite3 is required to merge the lexicon")
        }
        process.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(".mode ascii\nSELECT qstring, current, probability FROM unigrams;\n")
        }
        val code = process.waitFor()
        if (code != 0) error("sqlite3 exited $code")
        execOperations.javaexec {
            classpath = this@MergeLingxiChiaKeyTask.classpath
            mainClass.set("com.fskeyboard.ime.lingxi.LingxiLexiconMergeKt")
            args(
                lexicon.get().asFile.absolutePath,
                rows.absolutePath,
                mergedLexicon.get().asFile.absolutePath,
                marker.get().asFile.absolutePath,
            )
            jvmArgs("-Xmx2g")
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
