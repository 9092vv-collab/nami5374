@file:JvmName("ActivityComposeShimsKt")
package androidx.activity.compose

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

/** 뒤로 가기: 데스크톱에서는 Esc 키(또는 마우스 뒤로 버튼)가 가장 최근에 등록된 처리기를 부름. */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val cb = rememberUpdatedState(onBack)
    val en = rememberUpdatedState(enabled)
    val h = remember { space.sgu.next.DesktopBack.Handler({ en.value }, { cb.value() }) }
    DisposableEffect(h) { space.sgu.next.DesktopBack.add(h); onDispose { space.sgu.next.DesktopBack.remove(h) } }
}

@Composable
fun <I, O> rememberLauncherForActivityResult(contract: ActivityResultContract<I, O>, onResult: (O) -> Unit): ManagedActivityResultLauncher<I, O> {
    val cb = rememberUpdatedState(onResult)
    return remember(contract) { ManagedActivityResultLauncher(contract) { cb.value(it) } }
}

class ManagedActivityResultLauncher<I, O>(private val c: ActivityResultContract<I, O>, private val done: (O) -> Unit) : ActivityResultLauncher<I>() {
    override fun launch(input: I) { c.run(input, done) }
}
