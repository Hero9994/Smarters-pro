plugins {
    id("com.android.application")
}

android {
    namespace = "app.masahati.mobile"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.masahati.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.7.1"
    }

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
    testImplementation("junit:junit:4.13.2")
}
