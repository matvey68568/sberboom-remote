plugins {
    id("com.android.library")
}

android {
    namespace = "ru.sberboom.shared"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Только JVM-библиотеки: этот же код компилируется в Windows-сборке.
    api("com.squareup.okhttp3:okhttp:5.5.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
