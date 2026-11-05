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

group = providers.gradleProperty("litertlm.group").get()
version = providers.gradleProperty("litertlm.version").get()
val repository: String = providers.gradleProperty("litertlm.repository").get()

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

    compilerOptions {
        // PlatformContext is an expect class, because on Android it is a typealias of Context.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Same as :litertlm-kmp: Google's LiteRT-LM artifacts are Java 21 bytecode.
    jvmToolchain(21)
    jvm()

    android {
        namespace = providers.gradleProperty("litertlm.genai.namespace").get()
        compileSdk = 36
        minSdk = 24
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }

    iosArm64()
    iosSimulatorArm64()

    val xcframework = project(":litertlm-kmp").layout.buildDirectory.dir("clitertlm/CLiteRTLM.xcframework")
    targets.withType<KotlinNativeTarget>().configureEach {
        val slice = if (konanTarget == KonanTarget.IOS_SIMULATOR_ARM64) "ios-arm64-simulator" else "ios-arm64"
        // A test binary is its own app, so it links the runtime itself: a download first checks that the runtime is there.
        binaries.withType<TestExecutable>().configureEach {
            val frameworks = xcframework.get().dir(slice).asFile.absolutePath
            linkerOpts("-F$frameworks", "-framework", "CLiteRTLM", "-rpath", frameworks)
            linkTaskProvider.configure { dependsOn(":litertlm-kmp:fetchCLiteRTLM") }
        }
    }
    targets.withType<KotlinNativeTargetWithSimulatorTests>().configureEach {
        testRuns.configureEach { deviceId = simulatorDevice() }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":litertlm-kmp"))
            api(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.atomicfu)
            implementation(libs.kotlinx.io)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
        androidMain {
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
        }
        jvmMain {
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    // Maven Central requires signatures, from a key in ~/.gradle/gradle.properties. publishToMavenLocal needs no key and
    // does not touch one: on a version without -SNAPSHOT, signAllPublications() would make every publish require it.
    if (gradle.startParameter.taskNames.any { "MavenCentral" in it }) signAllPublications()
    coordinates(group.toString(), providers.gradleProperty("litertlm.genai.artifact").get(), version.toString())
    pom {
        name.set("litertlm-genai")
        description.set("Summarization, proofreading, rewriting, image description and prompts on the device, for Kotlin Multiplatform, on a LiteRT-LM model. With litertlm-genai-nano, also Gemini Nano on Android phones that have it")
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
