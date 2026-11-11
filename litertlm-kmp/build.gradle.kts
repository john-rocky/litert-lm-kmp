import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithSimulatorTests
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.konan.target.KonanTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.maven.publish)
}

val libraryNamespace: String = providers.gradleProperty("litertlm.namespace").get()
group = providers.gradleProperty("litertlm.group").get()
version = providers.gradleProperty("litertlm.version").get()
val repository: String = providers.gradleProperty("litertlm.repository").get()

/** Downloads Google's release of CLiteRTLM.xcframework, checks the sha256 and unpacks it. Never committed. */
abstract class FetchCLiteRTLM @Inject constructor(
    private val files: FileSystemOperations,
    private val archives: ArchiveOperations,
) : DefaultTask() {
    @get:Input abstract val url: Property<String>
    @get:Input abstract val sha256: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val zip = temporaryDir.resolve("CLiteRTLM.xcframework.zip")
        URI(url.get()).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256")
        zip.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == sha256.get()) { "CLiteRTLM.xcframework.zip: sha256 $actual, expected ${sha256.get()}" }
        files.sync {
            from(archives.zipTree(zip))
            into(outputDir)
        }
        zip.delete()
    }
}

val fetchCLiteRTLM = tasks.register<FetchCLiteRTLM>("fetchCLiteRTLM") {
    url = providers.gradleProperty("litertlm.ios.url")
    sha256 = providers.gradleProperty("litertlm.ios.sha256")
    outputDir = layout.buildDirectory.dir("clitertlm")
}

/**
 * The simulator for iosSimulatorArm64Test: -Plitertlm.simDevice=<udid>, or the first available iPhone. The plugin's
 * default device name does not exist on every Xcode.
 */
fun simulatorDevice(): String {
    providers.gradleProperty("litertlm.simDevice").orNull?.let { return it }
    val listing = providers.exec {
        commandLine("xcrun", "simctl", "list", "devices", "available")
        isIgnoreExitValue = true
    }.standardOutput.asText.getOrElse("")
    return Regex("iPhone[^(\\n]*\\(([0-9A-F-]{36})\\)").find(listing)?.groupValues?.get(1)
        ?: error("No iPhone simulator is available. Create one in Xcode, or pass -Plitertlm.simDevice=<udid>.")
}

kotlin {
    explicitApi()

    // litertlm-jvm 0.19.1 is Java 21 bytecode: anything older cannot load it.
    jvmToolchain(21)
    jvm()

    android {
        namespace = libraryNamespace
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.file("consumer-rules.pro")
        }
    }

    iosArm64()
    iosSimulatorArm64()

    val xcframework = layout.buildDirectory.dir("clitertlm/CLiteRTLM.xcframework")
    targets.withType<KotlinNativeTarget>().configureEach {
        val slice = if (konanTarget == KonanTarget.IOS_SIMULATOR_ARM64) "ios-arm64-simulator" else "ios-arm64"
        // Headers only: the runtime's functions are looked up in CLiteRTLM.framework, which the app links and embeds.
        compilations.getByName("main").cinterops.create("litertlm") {
            definitionFile = file("src/nativeInterop/cinterop/litertlm.def")
            includeDirs(file("src/nativeInterop/cinterop/headers"))
            packageName = "$libraryNamespace.cinterop"
        }
        // A test binary is its own app, so it links the framework itself, for the tests that load a model.
        binaries.withType<TestExecutable>().configureEach {
            val frameworks = xcframework.get().dir(slice).asFile.absolutePath
            linkerOpts("-F$frameworks", "-framework", "CLiteRTLM", "-rpath", frameworks)
            linkTaskProvider.configure { dependsOn(fetchCLiteRTLM) }
        }
    }
    targets.withType<KotlinNativeTargetWithSimulatorTests>().configureEach {
        testRuns.configureEach { deviceId = simulatorDevice() }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        // Android and the JVM share one delegate to Google's Kotlin API; each compiles it against its own artifact.
        androidMain {
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies { implementation(libs.litertlm.android) }
        }
        jvmMain {
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            dependencies { implementation(libs.litertlm.jvm) }
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    // Maven Central requires signatures, from a key in ~/.gradle/gradle.properties. publishToMavenLocal needs no key and
    // does not touch one: on a version without -SNAPSHOT, signAllPublications() would make every publish require it.
    if (gradle.startParameter.taskNames.any { "MavenCentral" in it }) signAllPublications()
    coordinates(group.toString(), providers.gradleProperty("litertlm.artifact").get(), version.toString())
    pom {
        name.set("litertlm-kmp")
        description.set("Kotlin Multiplatform API for Google's LiteRT-LM on-device LLM runtime: Engine, Conversation and EmbeddingEngine on Android, iOS and the JVM")
        inceptionYear.set("2026")
        url.set(repository)
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("john-rocky")
                name.set("Daisuke Majima")
                url.set("https://github.com/john-rocky")
            }
        }
        scm {
            url.set(repository)
            connection.set("scm:git:$repository.git")
            developerConnection.set("scm:git:$repository.git")
        }
    }
}
