import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
    id("org.jetbrains.compose") version "1.8.2"
}

kotlin { jvmToolchain(21) }

// 안드로이드판과 같은 소스를 그대로 씀. 안드로이드 전용 파일만 빼고 desktop/src 쪽 대체 파일·흉내(shim)로 채움.
sourceSets {
    main {
        kotlin {
            srcDir("../native/app/src/main/java")
            exclude("**/MainActivity.kt", "**/data/KeepAlive.kt", "**/data/Secrets.kt")
        }
        resources { srcDir("../native/app/src/main"); include("assets/**", "font/**", "icon/**") }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.androidx.navigation:navigation-compose:2.9.0-beta03")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}

compose.desktop {
    application {
        mainClass = "space.sgu.next.DesktopMainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Nami next"
            packageVersion = "1.0.0"
            vendor = "Nami"
            description = "Nami next (PC)"
            includeAllModules = true
            windows {
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f1c2b7e-3f7a-4d0e-9a51-2d8c0c5e7a10"
                menuGroup = "Nami next"
            }
        }
    }
}
