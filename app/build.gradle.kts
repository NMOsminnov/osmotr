// Приложение «Осмотр»: Kotlin (встроенный в AGP 9), Jetpack Compose. Снимает штатная камера телефона.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Ключ выпуска — вне репозитория (~/.config/osmotr-key): release.jks и store-password.
val releaseKey = File(System.getProperty("user.home"), ".config/osmotr-key")

android {
    namespace = "kg.osmotr"
    compileSdk = 37

    defaultConfig {
        applicationId = "kg.osmotr"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "2.9"
    }

    signingConfigs {
        if (File(releaseKey, "release.jks").exists()) create("release") {
            storeFile = File(releaseKey, "release.jks")
            storePassword = File(releaseKey, "store-password").readText().trim()
            keyAlias = "osmotr"
            keyPassword = storePassword
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Отдельные APK под архитектуры: телефону — только свой, меньше вес.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric на JDK 21 трогает внутренности java.base (FileDescriptor).
            it.jvmArgs(
                "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
            )
            it.maxHeapSize = "1536m"
        }
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    // Просмотр: зум с подгрузкой полного разрешения по месту — шильдик читается при ×12.
    implementation(libs.telephoto.coil3)
    implementation(libs.exifinterface)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
