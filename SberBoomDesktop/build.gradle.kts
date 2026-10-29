import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

group = "ru.sberboom"
version = "1.3"

kotlin {
    // Тот же общий слой, что и в Android-сборке (:shared).
    sourceSets["main"].kotlin.srcDir("../shared/src/main/java")
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)

    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.json:json:20240303")
    implementation("org.jmdns:jmdns:3.6.3")

    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "ru.sberboom.desktop.MainKt"

        // jpackage из JBR отсутствует (это урезанный JRE) — берём полный JDK 21,
        // если он есть рядом (tools/jdk21), иначе путь из SB_JDK.
        val bundledJdk = file("../tools/jdk21")
        val jdkPath = System.getenv("SB_JDK") ?: bundledJdk.absolutePath
        if (file(jdkPath).resolve("bin/jpackage.exe").exists()) {
            javaHome = jdkPath
        }

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "SberBoom Remote"
            packageVersion = "1.3.0"
            description = "Local control for SberBoom speakers over Wi-Fi"
            vendor = "SberBoom Remote"
            windows {
                menu = true
                shortcut = true
                // Иконку задаём отдельно, если появится .ico
                upgradeUuid = "8f2f0f4e-6a4f-4a1e-9c6b-0d3a5b9c7f21"
            }
        }
    }
}
