@file:JvmName("MakerPlatformShimsKt")
package androidx.compose.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf

/** 안드로이드 LocalContext 흉내: 앱 전체에 하나뿐인 Context. */
val LocalContext = staticCompositionLocalOf<android.content.Context> { space.nami.maker.DesktopApp.ctx }
