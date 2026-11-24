@file:JvmName("MakerActivityShimsKt")
package androidx.activity.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** 뒤로 가기: PC에서는 Esc 키(또는 마우스 뒤로 버튼)가 가장 최근에 등록된 처리기를 부름. */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val cb = rememberUpdatedState(onBack)
    val en = rememberUpdatedState(enabled)
    val h = remember { space.nami.maker.DesktopBack.Handler({ en.value }, { cb.value() }) }
    DisposableEffect(h) { space.nami.maker.DesktopBack.add(h); onDispose { space.nami.maker.DesktopBack.remove(h) } }
}
