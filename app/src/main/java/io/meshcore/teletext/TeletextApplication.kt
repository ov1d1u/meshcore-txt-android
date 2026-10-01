package io.meshcore.teletext

import android.app.Application

class TeletextApplication : Application() {
    val ble: MeshCoreBle by lazy { MeshCoreBle(this) }
}
