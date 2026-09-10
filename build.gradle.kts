import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
//
//group = "ds"
//version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    google()
}

dependencies {
    implementation("org.openani.jsystemthemedetector:jSystemThemeDetector:3.9")
    // Note, if you develop a library, you should use compose.desktop.common.
    // compose.desktop.currentOs should be used in launcher-sourceSet
    // (in a separate module for demo project and in testMain).
    // With compose.desktop.common you will also lose @Preview functionality
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("com.materialkolor:material-kolor:5.0.1")
    implementation("io.gitlab.kompose:tree-view:0.0.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    // for Dispatchers.Main
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            outputBaseDir.set(project.rootDir.resolve("out"))
            targetFormats(TargetFormat.AppImage, TargetFormat.Rpm, TargetFormat.Deb)
            packageName = "btrfs-file-restorer"
            packageVersion = "1.0.2"
            description = "Restores files from unmountable btrfs filesystem"
            linux {
                iconFile.set(File("btrfs-file-restorer.png"))
            }

        }
    }
}
