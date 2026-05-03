plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hanhira.ime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hanhira.ime"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "HANHIRA_LOG_ENABLED", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "HANHIRA_LOG_ENABLED", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures { buildConfig = true }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets", "src/main/assetsJap")
        }
    }

    androidResources {
        noCompress += listOf("ptl", "json", "xlsx", "csv")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.pytorch:pytorch_android_lite:2.1.0")
}
