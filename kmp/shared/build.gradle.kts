import com.android.build.api.dsl.LibraryExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    jvm()
    
    androidTarget {
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation("androidx.activity:activity-compose:1.9.1")
            implementation("androidx.biometric:biometric:1.2.0-alpha05")
            implementation("androidx.fragment:fragment-ktx:1.8.2")
            implementation("androidx.room:room-runtime:2.7.0")
            implementation("androidx.room:room-ktx:2.7.0")
            implementation("androidx.navigation:navigation-compose:2.8.0")
        }
        commonMain.dependencies {
            implementation("androidx.room:room-runtime:2.7.0")
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

extensions.configure<LibraryExtension>("android") {
    namespace = "com.jossephus.chuchu.shared"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
}

ksp {
    arg("room.schemaLocation", "$rootDir/../android/app/schemas")
    arg("room.incremental", "true")
}

dependencies {
    add("kspAndroid", "androidx.room:room-compiler:2.7.0")
    add("debugImplementation", libs.compose.uiTooling)
}
