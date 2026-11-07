package com.lagradost.cloudstream3.ui.player.live

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import com.lagradost.cloudstream3.mvvm.debugWarning
import java.util.WeakHashMap

object LiveHelper {
    private val liveManagers = WeakHashMap<Player, Pair<LiveManager, Player.Listener>>()

    @OptIn(UnstableApi::class)
    fun registerPlayer(player: Player?) {
        if (player == null) {
            debugWarning { "LiveHelper registerPlayer called with null player!" }
            return
        }

        // Prevent duplicates
        if (liveManagers.contains(player)) {
            return
        }

        val liveManager = LiveManager(player)
        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val window = Timeline.Window()
                timeline.getWindow(player.currentMediaItemIndex, window)
                if (window.isDynamic) {
                    liveManager.submitLivestreamChunk(LivestreamChunk(window.durationMs))
                }
                super.onTimelineChanged(timeline, reason)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                super.onPositionDiscontinuity(oldPosition, newPosition, reason)
                // Avoid recursive seeks and fighting against ExoPlayer's DefaultLivePlaybackSpeedControl.
                // DefaultLivePlaybackSpeedControl smoothly handles live edge drift (0.97x - 1.03x) without decoder flushes.
                // Only seek back if playback has severely desynced past the live window (> 15 seconds) and not already seeking.
                if (reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                    val timeAheadOfLive = liveManager.getTimeAheadOfLive(newPosition.positionMs)
                    if (timeAheadOfLive > 15_000L) {
                        player.seekTo(newPosition.positionMs - timeAheadOfLive)
                    }
                }
            }
        }

        synchronized(liveManagers) {
            player.addListener(listener)
            liveManagers[player] = liveManager to listener
        }
    }

    fun unregisterPlayer(player: Player?) {
        if (player == null) {
            debugWarning { "LiveHelper unregisterPlayer called with null player!" }
            return
        }

        // Prevent duplicates
        if (!liveManagers.contains(player)) {
            return
        }

        synchronized(liveManagers) {
            liveManagers[player]?.let { (_, listener) ->
                player.removeListener(listener)
            }
            liveManagers.remove(player)
        }
    }

    fun getLiveManager(player: Player?) = liveManagers[player]?.first
}
