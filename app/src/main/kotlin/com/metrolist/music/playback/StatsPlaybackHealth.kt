package com.metrolist.music.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.metrolist.music.utils.StatsLog
import timber.log.Timber
import java.lang.ref.WeakReference

/** A little slack, so a report that races the resume it belongs to is still read as one. */
private const val AUDIO_RESUME_SLACK_MS = 250L

/**
 * Whether an underrun report is the sink being picked up again rather than audio breaking.
 *
 * Media3 raises onAudioUnderrun from the audio sink with the time since the sink was last fed, and
 * nothing feeds a sink that is not playing. A pause therefore leaves that clock running, and the
 * first report after the resume carries the whole idle stretch — gaps of six and twenty-one
 * minutes have been seen this way, which nobody could have heard.
 *
 * Rather than guess a threshold, the gap is compared with how long playback has actually been
 * running. One reaching back past the moment playback started did not happen during playback.
 */
internal fun isAudioResumeArtefact(
    elapsedSinceLastFeedMs: Long,
    playingForMs: Long?,
): Boolean {
    if (elapsedSinceLastFeedMs <= 0L) return true
    val playingFor = playingForMs ?: return true
    return elapsedSinceLastFeedMs > playingFor + AUDIO_RESUME_SLACK_MS
}

/**
 * Records stalls, so a comparison does not rest on what someone remembers hearing.
 *
 * The first side-by-side run measured the wire and nothing else, and it showed this build
 * fetching whole files where the other fetched bounded ranges — two of seven songs needed a
 * second request part way through. That the listener heard those two as gaps is exactly the kind
 * of claim a log should carry rather than a person.
 *
 * Purely observational: it reads player state on callbacks it is given and never touches
 * playback. Field names match the other client's line for line, so one parser reads both.
 */
class StatsPlaybackHealth : Player.Listener, AnalyticsListener {
    private var playerRef: WeakReference<Player>? = null
    private var bufferingStartedAtMs: Long? = null
    private var playingSinceMs: Long? = null

    fun attachTo(player: ExoPlayer) {
        playerRef = WeakReference(player)
        player.addListener(this)
        player.addAnalyticsListener(this)
    }

    private fun bufferedAheadMs(player: Player): Long =
        (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        playingSinceMs = if (isPlaying) SystemClock.elapsedRealtime() else null
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (!StatsLog.isEnabled) return
        Timber.tag("PlaybackHealth").i(
            "track id=%s reason=%d",
            mediaItem?.mediaId ?: "none",
            reason,
        )
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (!StatsLog.isEnabled) return
        val player = playerRef?.get() ?: return
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                bufferingStartedAtMs = SystemClock.elapsedRealtime()
                Timber.tag("PlaybackHealth").w(
                    "buffering-start id=%s posMs=%d bufferedAheadMs=%d totalBufferedMs=%d " +
                        "isLoading=%s playWhenReady=%s state=%d",
                    player.currentMediaItem?.mediaId ?: "none",
                    player.currentPosition,
                    bufferedAheadMs(player),
                    player.totalBufferedDuration,
                    player.isLoading,
                    player.playWhenReady,
                    playbackState,
                )
            }

            Player.STATE_READY -> {
                val startedAt = bufferingStartedAtMs ?: return
                bufferingStartedAtMs = null
                Timber.tag("PlaybackHealth").w(
                    "buffering-end durationMs=%d id=%s posMs=%d bufferedAheadMs=%d " +
                        "totalBufferedMs=%d isLoading=%s playWhenReady=%s state=%d",
                    (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L),
                    player.currentMediaItem?.mediaId ?: "none",
                    player.currentPosition,
                    bufferedAheadMs(player),
                    player.totalBufferedDuration,
                    player.isLoading,
                    player.playWhenReady,
                    playbackState,
                )
            }

            else -> bufferingStartedAtMs = null
        }
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        if (!StatsLog.isEnabled) return
        val player = playerRef?.get() ?: return
        val playingForMs = playingSinceMs?.let { SystemClock.elapsedRealtime() - it }

        if (isAudioResumeArtefact(elapsedSinceLastFeedMs, playingForMs)) {
            Timber.tag("PlaybackHealth").d(
                "audio-resume-after-idle id=%s posMs=%d idleMs=%d playingForMs=%d",
                player.currentMediaItem?.mediaId ?: "none",
                player.currentPosition,
                elapsedSinceLastFeedMs,
                playingForMs ?: -1L,
            )
            return
        }

        Timber.tag("PlaybackHealth").e(
            "AUDIO UNDERRUN id=%s posMs=%d bufferedAheadMs=%d totalBufferedMs=%d isLoading=%s " +
                "bufferBytes=%d outputBufferMs=%s elapsedSinceLastFeedMs=%d playingForMs=%d",
            player.currentMediaItem?.mediaId ?: "none",
            player.currentPosition,
            bufferedAheadMs(player),
            player.totalBufferedDuration,
            player.isLoading,
            bufferSize,
            if (bufferSizeMs == C.TIME_UNSET) "unset" else bufferSizeMs.toString(),
            elapsedSinceLastFeedMs,
            playingForMs ?: -1L,
        )
    }
}
