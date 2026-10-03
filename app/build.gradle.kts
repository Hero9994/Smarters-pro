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

// Published, explicitly licensed inference assets; pin both source commit and SHA-256.
val scannerAssets = layout.buildDirectory.dir("generated/scannerAssets")
val prepareScannerModels by tasks.registering {
    val revision = "01bebd394b9dd6f3a692f28aea7c0638085eb4da"
    val assets = mapOf(
        "docquad.ort" to Pair("app/src/main/assets/docquad/docquadnet256_trained_opset17.ort", "f0f2f52d7d79ff02d346c8f9d0c9e903407366aeea1747cdcff160c401e3e72a"),
        "paddle-det.ort" to Pair("app/src/paddle/assets/paddleocr/v5/det.ort", "bfb226a460dee7e50b210e20e7c51becff55798150aea45cb9d047c81bfb9c9a"),
        "paddle-latin.ort" to Pair("app/src/paddle/assets/paddleocr/v5/latin_PP-OCRv5_mobile_rec.ort", "5bb93e0fef6fcde14ddadfec23ff9efbc331531ba1ae54baba85605d7794efda"),
        "paddle-arabic.ort" to Pair("app/src/paddle/assets/paddleocr/v5/arabic_PP-OCRv5_mobile_rec.ort", "17d31ec78b3dd2168c97595031fdf7adeba145c4cfa5f33278f04e8363fdea9d"),
        "latin_dict.txt" to Pair("app/src/paddle/assets/paddleocr/v5/latin_PP-OCRv5_mobile_rec_dict.txt", "b95923300a0656f8169feee90143cbfcdb62d82a37b54e6b12c224c3e584916f"),
        "arabic_dict.txt" to Pair("app/src/paddle/assets/paddleocr/v5/arabic_PP-OCRv5_mobile_rec_dict.txt", "2a215ea5877f01b1f8c8803783cda73707222c39a84d4a6cfee9ef502c48248e")
    )
    inputs.property("revision", revision); inputs.property("assets", assets)
    outputs.dir(scannerAssets)
    doLast {
        val directory = scannerAssets.get().dir("scanner/models").asFile.apply { mkdirs() }
        fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { stream ->
                val buffer = ByteArray(65536)
                while (true) { val count = stream.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
        assets.forEach { (name, source) ->
            val target = directory.resolve(name)
            if (!target.isFile || digest(target) != source.second) {
                val temporary = directory.resolve("$name.download")
                val connection = URI("https://raw.githubusercontent.com/egdels/makeacopy/$revision/${source.first}").toURL().openConnection()
                connection.connectTimeout = 30_000; connection.readTimeout = 90_000
                try {
                    connection.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
                    check(digest(temporary) == source.second) { "Scanner asset checksum mismatch: $name" }
                    temporary.copyTo(target, overwrite = true)
                } finally { temporary.delete() }
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(prepareScannerModels) }

val prepareUvDocModel by tasks.registering(Exec::class) {
    val output=layout.buildDirectory.file("generated/uvdocAssets/scanner/models/uvdoc-grid.onnx").get().asFile
    inputs.files(rootProject.file("tools/scanner/prepare_uvdoc.py"),rootProject.file("tools/scanner/export_uvdoc.py"))
    outputs.file(output)
    commandLine("python3",rootProject.file("tools/scanner/prepare_uvdoc.py"),"--output",output,
        "--cache",layout.buildDirectory.dir("uvdoc-converter").get().asFile)
}
tasks.named("preBuild").configure { dependsOn(prepareUvDocModel) }

android {
    namespace = "app.masahati.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.masahati.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "alpha-0.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    sourceSets.getByName("main").assets.directories.add(ocrAssets.get().asFile.absolutePath)
    sourceSets.getByName("main").assets.directories.add(scannerAssets.get().asFile.absolutePath)
    sourceSets.getByName("main").assets.directories.add(layout.buildDirectory.dir("generated/uvdocAssets").get().asFile.absolutePath)
    sourceSets.getByName("androidTest").assets.directories.add(layout.buildDirectory.dir("generated/scannerBenchmarkAssets").get().asFile.absolutePath)

    buildTypes {
        debug {
            applicationIdSuffix = ".v07"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
        }
        create("preview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            matchingFallbacks += listOf("release")
            // CI may sign its emulator copy. Distributable previews are unsigned here,
            // then signed outside CI with the persistent preview key (never committed).
            signingConfig = if (providers.gradleProperty("previewCiTest").orNull == "true") signingConfigs.getByName("debug") else null
            if (providers.gradleProperty("previewCiTest").orNull != "true") {
                ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
            }
        }
    }

    testBuildType = if (providers.gradleProperty("previewCiTest").orNull == "true") "preview" else "debug"

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
    implementation("androidx.exifinterface:exifinterface:1.4.2")
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
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.1")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
    implementation("androidx.camera:camera-extensions:1.6.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
}
