import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm()

    android {
        namespace = "${providers.gradleProperty("litertlm.namespace").get()}.sample.shared"
        compileSdk = 37
        minSdk = 31
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }

    iosArm64()
    iosSimulatorArm64()
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("sampleplatform") {
            definitionFile = file("src/nativeInterop/cinterop/sampleplatform.def")
            packageName = "io.github.johnrocky.litertlm.sample.cinterop"
        }
        binaries.framework {
            baseName = "SampleShared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":litertlm-genai"))
            implementation(libs.serialization.json)
            implementation(libs.kotlinx.io)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
        }
        androidMain.dependencies {
            implementation(project(":litertlm-genai-nano"))
            implementation(libs.androidx.activity.compose)
        }
    }
}

/** DeviceCheck on this machine: -PdeviceCheckArgs="--model /path/to/model.litertlm --workdir /path/to/dir" */
tasks.register<JavaExec>("runDeviceCheckJvm") {
    group = "verification"
    val main = kotlin.jvm().compilations.getByName("main")
    classpath = main.output.allOutputs + main.runtimeDependencyFiles!!
    mainClass = "io.github.johnrocky.litertlm.sample.DeviceCheckMainKt"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    args = providers.gradleProperty("deviceCheckArgs").orNull?.trim()?.split(Regex("\\s+")).orEmpty()
}
