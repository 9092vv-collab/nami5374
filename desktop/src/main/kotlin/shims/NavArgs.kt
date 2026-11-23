@file:JvmName("NavArgShimsKt")
package space.sgu.next.ui

import androidx.savedstate.SavedState
import androidx.savedstate.read

/** 안드로이드 Bundle.getString 흉내 (멀티플랫폼 내비게이션의 SavedState). */
fun SavedState.getString(key: String): String? = read { getStringOrNull(key) }
