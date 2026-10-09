// Общее для Android и iPhone: описи (подбор столбцов), Excel, поиск, интерфейс.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    jvm()
    android {
        namespace = "kg.osmotr.shared"
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.components.resources)
            implementation(libs.okio)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.atomicfu)
            implementation(libs.coil.compose.mp)
            implementation(libs.telephoto.zoomable)
            implementation(libs.compose.icons.mp)
            implementation(libs.lifecycle.runtime.compose.mp)
            implementation(libs.compose.backhandler.mp)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.exifinterface)
            implementation(libs.telephoto.coil3)
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

tasks.withType<Test>().configureEach {
    // Набор кривых описей — тот же, что у Android-тестов.
    systemProperty("templates", rootProject.file("shared/src/jvmTest/resources/templates").absolutePath)
}

// Шрифт и прочие ресурсы общего кода — класс Res в своём пакете, наружу не виден.
compose.resources {
    packageOfResClass = "kg.osmotr.ui.res"
    publicResClass = false
}
