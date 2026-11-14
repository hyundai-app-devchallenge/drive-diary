package com.dongbantest.app.debug.music

import android.content.Context
import com.dongbantest.app.music.MusicListenerService
import com.dongbantest.app.music.NowPlayingWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Activity의 수명과 분리한다. HOME/화면 회전 후에도 같은 관찰자와 로그를 유지한다. */
internal object MusicLabProbe {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var instance: NowPlayingWatcher? = null

    fun watcher(context: Context): NowPlayingWatcher {
        return instance ?: NowPlayingWatcher(context.applicationContext).also { watcher ->
            instance = watcher
            scope.launch {
                MusicListenerService.connected.collect { connected ->
                    if (connected) watcher.refresh() else watcher.stop()
                }
            }
        }
    }
}
