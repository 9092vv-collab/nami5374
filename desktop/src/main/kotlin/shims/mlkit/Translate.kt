package com.google.mlkit.nl.translate

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions

object TranslateLanguage { const val ENGLISH = "en"; const val KOREAN = "ko" }

class TranslatorOptions private constructor() {
    class Builder {
        fun setSourceLanguage(l: String) = this
        fun setTargetLanguage(l: String) = this
        fun build() = TranslatorOptions()
    }
}

class Translator {
    private fun no() = Exception("PC에서는 폰 안 번역(ML Kit)을 쓸 수 없어요")
    fun downloadModelIfNeeded(c: DownloadConditions): Task<Void?> = Task(no())
    fun downloadModelIfNeeded(): Task<Void?> = Task(no())
    fun translate(text: String): Task<String> = Task(no())
    fun close() {}
}

object Translation { fun getClient(o: TranslatorOptions) = Translator() }
