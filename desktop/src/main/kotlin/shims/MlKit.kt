// ML Kit(폰 안 번역)은 PC에 없음 → 항상 실패하게 두면 KoTranslate가 구글 번역만 씀.
package com.google.android.gms.tasks

class Task<T>(private val error: Exception) {
    fun addOnSuccessListener(l: (T) -> Unit): Task<T> = this
    fun addOnFailureListener(l: (Exception) -> Unit): Task<T> { l(error); return this }
}
