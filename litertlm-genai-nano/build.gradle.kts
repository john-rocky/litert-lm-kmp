import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.maven.publish)
}

group = providers.gradleProperty("litertlm.group").get()
version = providers.gradleProperty("litertlm.version").get()
val repository: String = providers.gradleProperty("litertlm.repository").get()

kotlin {
    explicitApi()

    android {
        namespace = providers.gradleProperty("litertlm.genai.nano.namespace").get()
        compileSdk = 36
        // ML Kit's GenAI artifacts declare minSdkVersion 26. An app without this artifact can stay on 24.
        minSdk = 26
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.file("consumer-rules.pro")
        }
    }

    sourceSets {
        androidMain.dependencies {
            api(project(":litertlm-genai"))
            implementation(libs.mlkit.genai.common)
            implementation(libs.mlkit.genai.summarization)
            implementation(libs.mlkit.genai.proofreading)
            implementation(libs.mlkit.genai.rewriting)
            implementation(libs.mlkit.genai.image.description)
            implementation(libs.mlkit.genai.prompt)
            implementation(libs.coroutines.guava)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    // Maven Central requires signatures, from a key in ~/.gradle/gradle.properties. publishToMavenLocal needs no key and
    // does not touch one: on a version without -SNAPSHOT, signAllPublications() would make every publish require it.
    if (gradle.startParameter.taskNames.any { "MavenCentral" in it }) signAllPublications()
    coordinates(group.toString(), providers.gradleProperty("litertlm.genai.nano.artifact").get(), version.toString())
    pom {
        name.set("litertlm-genai-nano")
        description.set("Gemini Nano for litertlm-genai on Android phones that have it, through ML Kit GenAI")
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
