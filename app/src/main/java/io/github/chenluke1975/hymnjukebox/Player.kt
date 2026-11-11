package io.github.chenluke1975.hymnjukebox

import android.os.Handler
import android.os.Looper

/** Shared queue/state between the UI and [PlayerService] (same process, main thread). */
object Player {
    /** Either an official album preview (streamed) or a file on the phone (content:// URI). */
    data class Item(val title: String, val album: Album? = null, val localUri: String? = null) {
        val isLocal: Boolean get() = localUri != null
        companion object {
            fun of(a: Album) = Item(a.title, album = a)
        }
    }

    enum class Status { IDLE, LOADING, PLAYING, PAUSED, ERROR }

    val queue = mutableListOf<Item>()
    var index = -1
    var status = Status.IDLE
    var message = ""

    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    val current: Item? get() = queue.getOrNull(index)

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    fun changed() { main.post { listeners.toList().forEach { it() } } }
}
