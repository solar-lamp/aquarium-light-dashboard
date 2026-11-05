plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The dashboard lives at the repository root and is shared with the website.
// It's copied into the APK at build time, so there's only one copy to edit.
val webRoot = rootProject.projectDir.parentFile
val webAssetsDir = layout.buildDirectory.dir("generated/webAssets")

val copyWebAssets by tasks.registering(Sync::class) {
    from(webRoot) {
        // config.js is deliberately left out: the app serves its own,
        // built from the broker details entered in Settings.
        include("index.html", "style.css", "script.js", "aquarium.jpg")
    }
    into(webAssetsDir.map { it.dir("web") })
}

tasks.named("preBuild") {
    dependsOn(copyWebAssets)
}

// Release signing comes from environment variables (set by CI from
// repository secrets). Without them the release build uses the debug key.
val releaseKeystore: String? = System.getenv("SIGNING_KEYSTORE_PATH")

android {
    namespace = "com.solarlamp.aquariumlight"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.solarlamp.aquariumlight"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0-dev"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets.getByName("main").assets.srcDir(webAssetsDir)

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
}
