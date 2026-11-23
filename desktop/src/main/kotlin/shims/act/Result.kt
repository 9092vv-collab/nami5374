package androidx.activity.result

abstract class ActivityResultLauncher<I> {
    abstract fun launch(input: I)
    fun launch(input: I, options: Any?) = launch(input)
    open fun unregister() {}
}
