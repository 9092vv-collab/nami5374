package com.google.mlkit.common.model

class DownloadConditions private constructor() {
    class Builder { fun requireWifi() = this; fun build() = DownloadConditions() }
}
