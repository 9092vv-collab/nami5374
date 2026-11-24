import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0"
    id("org.jetbrains.compose") version "1.8.2"
}

kotlin { jvmToolchain(21) }

// 폰판(maker/)과 같은 소스를 그대로 씀. 안드로이드 전용 파일만 빼고 이쪽 대체 파일·흉내(shim)로 채움 (Nami next PC판과 같은 방식).
sourceSets {
    main {
        kotlin {
            srcDir("../maker/app/src/main/java")
            exclude("**/MainActivity.kt", "**/Secrets.kt", "**/Platform.kt")
        }
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.json:json:20240303")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
}

compose.desktop {
    application {
        mainClass = "space.nami.maker.DesktopMainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "Nami Maker"
            packageVersion = "1.0.0"
            vendor = "Nami"
            description = "Nami Maker (PC)"
            includeAllModules = true
        }
    }
}
