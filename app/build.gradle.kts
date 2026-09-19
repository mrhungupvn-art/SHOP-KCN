plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val apiBaseUrl = System.getenv("API_BASE_URL").takeUnless { it.isNullOrBlank() } ?: "https://com11h.com"
val keystoreFile = file("release.keystore")

android {
    namespace = "com.com11h.shop"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.com11h.shop"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }
    buildFeatures { buildConfig = true }
    lint {
        // lintVitalAnalyzeRelease cũng rất tốn RAM và không bắt buộc để ra
        // được file APK; tắt để tránh cộng dồn OutOfMemoryError trên CI.
        // Vẫn có thể chạy "lint" thủ công (Android Studio hoặc ./gradlew lint)
        // khi cần kiểm tra chất lượng code.
        checkReleaseBuilds = false
    }
    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
