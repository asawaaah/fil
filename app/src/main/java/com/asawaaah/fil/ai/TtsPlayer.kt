package com.asawaaah.fil.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

/** What is being read: one article, cut into [segments], read in [language]. */
data class ReadAloudContent(
    val articleId: Long,
    val title: String,
    val source: String,
    val imageUrl: String?,
    val language: String,
    val segments: List<Segment>,
)

/**
 * A media "player" that reads text with the phone's text-to-speech engine, one segment per
 * playlist item, so that the notification, the lock screen and headphone buttons can play,
 * pause and skip like with music.
 */
@OptIn(UnstableApi::class)
class TtsPlayer(private val context: Context) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    var content: ReadAloudContent? = null
        private set
    private var index = 0
    private var playWhenReady = false
    private var ended = false
    private var rate = 1f

    /** Set when the voice for the language is missing; null when all is fine. */
    var voiceProblem: String? = null
        private set

    /** Called on the main thread whenever something visible changes. */
    var onChange: () -> Unit = {}

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            // A call, a voice message, another app's music: pause.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
            ) {
                if (playWhenReady) pause()
            }
        }, main)
        .build()

    /** Starts the voice engine early, so that reading starts at once. */
    fun warmUp() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            main.post {
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) voiceProblem = "Aucun moteur de synthèse vocale n'est disponible."
                applyLanguage()
                if (playWhenReady) speakCurrent()
                changed()
            }
        }.apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    main.post { onSegmentDone(utteranceId) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post { onSegmentDone(utteranceId) }
                }
            })
        }
    }

    fun load(newContent: ReadAloudContent, startAt: Int = 0) {
        tts?.stop()
        content = newContent
        index = startAt.coerceIn(0, (newContent.segments.size - 1).coerceAtLeast(0))
        ended = false
        voiceProblem = null
        applyLanguage()
        changed()
    }

    fun currentIndex(): Int = index
    fun isReading(): Boolean = playWhenReady && !ended
    fun rate(): Float = rate

    private fun applyLanguage() {
        val engine = tts ?: return
        val lang = content?.language ?: return
        if (!ttsReady) return
        val result = engine.setLanguage(Locale.forLanguageTag(lang))
        voiceProblem = when (result) {
            TextToSpeech.LANG_MISSING_DATA -> "La voix ${Languages.name(lang)} n'est pas installée sur ton téléphone."
            TextToSpeech.LANG_NOT_SUPPORTED -> "Ton moteur vocal ne sait pas lire ${Languages.name(lang)}."
            else -> null
        }
        engine.setSpeechRate(rate)
    }

    private fun utteranceId(i: Int) = "${content?.articleId}:$i"

    private fun speakCurrent() {
        val engine = tts ?: return
        if (!ttsReady) return
        val segments = content?.segments ?: return
        val segment = segments.getOrNull(index) ?: return
        engine.setSpeechRate(rate)
        engine.speak(segment.text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId(index))
    }

    private fun onSegmentDone(id: String?) {
        if (id != utteranceId(index) || !playWhenReady) return
        val size = content?.segments?.size ?: 0
        if (index + 1 < size) {
            index++
            speakCurrent()
        } else {
            ended = true
            playWhenReady = false
            audio.abandonAudioFocusRequest(focusRequest)
        }
        changed()
    }

    private fun changed() {
        invalidateState()
        onChange()
    }

    override fun getState(): State {
        val c = content
        val items = c?.segments?.mapIndexed { i, s ->
            MediaItemData.Builder("${c.articleId}-$i")
                .setMediaItem(MediaItem.Builder().setMediaId("${c.articleId}-$i").build())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(c.title)
                        .setArtist(c.source)
                        .setSubtitle(s.text.take(80))
                        .setArtworkUri(c.imageUrl?.let(Uri::parse))
                        .build(),
                )
                .build()
        }.orEmpty()
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
                Player.COMMAND_SET_SPEED_AND_PITCH,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_RELEASE,
            )
            .build()
        val builder = State.Builder()
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaylist(items)
            .setPlaybackParameters(PlaybackParameters(rate))
            .setPlaybackState(
                when {
                    items.isEmpty() -> Player.STATE_IDLE
                    ended -> Player.STATE_ENDED
                    else -> Player.STATE_READY
                },
            )
        if (items.isNotEmpty()) builder.setCurrentMediaItemIndex(index).setContentPositionMs(0)
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            if (ended) {
                index = 0
                ended = false
            }
            audio.requestAudioFocus(focusRequest)
            this.playWhenReady = true
            speakCurrent()
        } else {
            this.playWhenReady = false
            tts?.stop()
            audio.abandonAudioFocusRequest(focusRequest)
        }
        onChange()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val size = content?.segments?.size ?: 0
        if (size > 0) {
            index = mediaItemIndex.coerceIn(0, size - 1)
            ended = false
            if (playWhenReady) speakCurrent() else tts?.stop()
        }
        onChange()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        setRate(playbackParameters.speed)
        return Futures.immediateVoidFuture()
    }

    fun setRate(value: Float) {
        rate = value.coerceIn(0.5f, 3f)
        // Takes effect on the current sentence right away.
        if (playWhenReady) speakCurrent()
        changed()
    }

    override fun handleStop(): ListenableFuture<*> {
        playWhenReady = false
        tts?.stop()
        audio.abandonAudioFocusRequest(focusRequest)
        content = null
        index = 0
        onChange()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        audio.abandonAudioFocusRequest(focusRequest)
        return Futures.immediateVoidFuture()
    }
}
