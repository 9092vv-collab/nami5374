@file:JvmName("MakerWindowShimsKt")
package androidx.compose.ui.window

/** 안드로이드 전용 decorFitsSystemWindows 인자를 받아 주고 무시. */
fun DialogProperties(
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    usePlatformDefaultWidth: Boolean = true,
    decorFitsSystemWindows: Boolean,
): DialogProperties = DialogProperties(dismissOnBackPress = dismissOnBackPress, dismissOnClickOutside = dismissOnClickOutside, usePlatformDefaultWidth = usePlatformDefaultWidth)
