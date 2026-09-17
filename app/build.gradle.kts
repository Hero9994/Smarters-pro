import java.io.File
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
}

// Bundle the small, pinned language packs in the APK: OCR needs no network on the phone.
val ocrAssets = layout.buildDirectory.dir("generated/ocrAssets")
val prepareOcrModels by tasks.registering {
    val revision = "87416418657359cb625c412a48b6e1d6d41c29bd"
    val models = mapOf(
        "ara" to "e3206d3dc87fd50c24a0fb9f01838615911d25168f4e64415244b67d2bb3e729",
        "deu" to "19d219bbb6672c869d20a9636c6816a81eb9a71796cb93ebe0cb1530e2cdb22d",
        "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
    )
    inputs.property("revision", revision)
    inputs.property("models", models)
    outputs.dir(ocrAssets)
    doLast {
        val directory = ocrAssets.get().dir("ocr/tessdata").asFile.apply { mkdirs() }
        models.forEach { (language, expectedHash) ->
            val target = directory.resolve("$language.traineddata")
            fun digest(file: File): String = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            if (!target.isFile || digest(target) != expectedHash) {
                val temporary = directory.resolve("$language.download")
                val connection = URI("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/$revision/$language.traineddata").toURL().openConnection()
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                try {
                    connection.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                    check(digest(temporary) == expectedHash) { "OCR model checksum mismatch: $language" }
                    temporary.copyTo(target, overwrite = true)
                } finally { temporary.delete() }
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(prepareOcrModels) }

android {
    namespace = "app.masahati.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.masahati.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "alpha-0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("main").assets.directories.add(ocrAssets.get().asFile.absolutePath)

    buildTypes {
        debug {
            applicationIdSuffix = ".v07"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        disable += setOf("OldTargetApi")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.0")
    implementation("androidx.work:work-runtime:2.11.2")
    implementation("com.google.mediapipe:tasks-text:1.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("com.google.zxing:core:3.5.4")
    implementation("io.michaelrocks:libphonenumber-android:9.0.36")
    implementation("org.apache.commons:commons-text:1.15.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.github.pemistahl:lingua:1.2.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }
    implementation("androidx.metrics:metrics-performance:1.0.0")
    implementation("com.github.anrwatchdog:anrwatchdog:1.4.0")
    implementation("org.opencv:opencv:4.12.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}
