import org.jetbrains.kotlin.gradle.dsl.JvmTarget

version = project.property("VERSION_NAME") as String
group = "io.github.oguzhaneksi"

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.jetbrains.dokka)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

detekt {
    config.setFrom("$rootDir/config/detekt/detekt.yml")
    buildUponDefaultConfig = true
}

kotlin {
    android {
        namespace = "com.streamprobe.sdk"
        compileSdk = 37
        minSdk = 23

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }

        withHostTest {
            isIncludeAndroidResources = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.atomicfu)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
            implementation(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.exoplayer.hls)
            implementation(libs.androidx.media3.exoplayer.dash)
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.recyclerview)
            implementation(libs.androidx.appcompat)
        }
        val androidHostTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.mockito.core)
                implementation(libs.robolectric)
            }
        }
    }
}

mavenPublishing {
    coordinates(
        groupId = "io.github.oguzhaneksi",
        artifactId = "streamprobe",
    )

    pom {
        name.set("StreamProbe")
        description.set(
            "A debug SDK for Android apps that inspects HLS and DASH streaming traffic in real time on top of Media3/ExoPlayer.",
        )
        inceptionYear.set("2026")
        url.set("https://github.com/oguzhaneksi/StreamProbe")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("oguzhaneksi")
                name.set("Oğuzhan Ekşi")
                url.set("https://github.com/oguzhaneksi")
            }
        }
        scm {
            url.set("https://github.com/oguzhaneksi/StreamProbe")
            connection.set("scm:git:https://github.com/oguzhaneksi/StreamProbe.git")
            developerConnection.set("scm:git:ssh://git@github.com/oguzhaneksi/StreamProbe.git")
        }
    }

    publishToMavenCentral()
    signAllPublications()
}
