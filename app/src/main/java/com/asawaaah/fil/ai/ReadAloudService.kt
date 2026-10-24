package com.asawaaah.fil.ai

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.asawaaah.fil.FilApp
import com.asawaaah.fil.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the reader shows of the reading in progress. */
data class ReadAloudState(
    val articleId: Long? = null,
    val segments: List<Segment> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val rate: Float = 1f,
    val voiceProblem: String? = null,
) {
    val currentBlock: Int? get() = if (articleId == null) null else segments.getOrNull(index)?.block
}

/**
 * Reading aloud for the whole app: one player, kept by a media service so that it goes on with
 * the screen off, with controls in the notification and on the lock screen.
 */
class ReadAloud(private val context: Context) {
    private val _state = MutableStateFlow(ReadAloudState())
    val state: StateFlow<ReadAloudState> = _state

    val player: TtsPlayer by lazy {
        TtsPlayer(context).also { p -> p.onChange = { publish() } }
    }

    private fun publish() {
        val p = player
        val c = p.content
        _state.value = ReadAloudState(
            articleId = c?.articleId,
            segments = c?.segments.orEmpty(),
            index = p.currentIndex(),
            playing = p.isReading(),
            rate = p.rate(),
            voiceProblem = p.voiceProblem,
        )
    }

    /** Prepares the voice engine while the article is being read. */
    fun warmUp() = player.warmUp()

    fun start(content: ReadAloudContent, fromSegment: Int = 0) {
        // The service keeps the reading alive and shows the controls.
        context.startService(Intent(context, ReadAloudService::class.java))
        player.warmUp()
        player.load(content, fromSegment)
        player.play()
    }

    fun toggle() = if (player.isReading()) player.pause() else player.play()
    fun next() = player.seekToNextMediaItem()
    fun previous() = player.seekToPreviousMediaItem()
    fun seekTo(segment: Int) = player.seekTo(segment, 0)
    fun setRate(rate: Float) = player.setRate(rate)
    fun stop() {
        player.stop()
        context.stopService(Intent(context, ReadAloudService::class.java))
    }
}

@OptIn(UnstableApi::class)
class ReadAloudService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val readAloud = (application as FilApp).container.readAloud
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, readAloud.player)
            .setSessionActivity(open)
            .build()
            // Attached right away (not only when a controller connects), so that the notification,
            // the lock screen controls and the foreground service start with the reading.
            .also { addSession(it) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away stops a paused reading; a reading in progress goes on.
        if (session?.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            // The player lives in the app container and is reused: only the session is released.
            release()
        }
        session = null
        super.onDestroy()
    }
}
