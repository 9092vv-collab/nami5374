@file:JvmName("ComposePlatformShimsKt")
package androidx.compose.ui.platform

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf

/** 안드로이드 LocalContext 흉내: 데스크톱 앱 전체에 하나뿐인 Context. */
val LocalContext = staticCompositionLocalOf<android.content.Context> { space.sgu.next.DesktopApp.ctx }

class Configuration(@JvmField val screenWidthDp: Int, @JvmField val screenHeightDp: Int)
/** 창 크기(dp)를 데스크톱 창이 채워 줌. */
val LocalConfiguration = compositionLocalOf { Configuration(1200, 800) }

class View { var keepScreenOn: Boolean = false }
val LocalView = staticCompositionLocalOf { View() }
