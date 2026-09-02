plugins {
    id("com.android.application")
}

android {
    namespace = "app.masahati.mobile"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.masahati.mobile"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
