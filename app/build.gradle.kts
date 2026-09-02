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
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        // API 37 is preview-only in the current SDK channel. Stay on stable API 36 until 37 is stable.
        disable += "OldTargetApi"
        disable += "GradleDependency"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
