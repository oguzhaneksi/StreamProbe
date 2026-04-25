plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    alias(libs.plugins.jetbrains.dokka)
}

android {
    namespace = "com.streamprobe.sdk"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 23

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.appcompat)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.robolectric)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

mavenPublishing {
    coordinates(
        groupId = "io.github.oguzhaneksi",
        artifactId = "streamprobe",
        version = project.property("VERSION_NAME") as String
    )

    pom {
        name.set("StreamProbe")
        description.set("A debug SDK for Android apps that inspects HLS and DASH streaming traffic in real time on top of Media3/ExoPlayer.")
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

    // Configure publishing to Maven Central Portal
    publishToMavenCentral()

    // Enable GPG signing for all publications
    signAllPublications()
}