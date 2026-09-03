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
        versionCode = 4
        versionName = "0.4.0"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".v04"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        lintConfig = file("../lint.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JaVaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    testImplementation("junit:junit:4.13.2")
}
