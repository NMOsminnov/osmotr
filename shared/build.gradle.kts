// Общее для Android и iPhone: описи (подбор столбцов), Excel, поиск, интерфейс.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
    jvm()
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
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit"))
        }
    }
}

tasks.withType<Test>().configureEach {
    // Набор кривых описей — тот же, что у Android-тестов.
    systemProperty("templates", rootProject.file("app/src/test/resources/templates").absolutePath)
}
