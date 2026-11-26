package com.lagradost.cloudstream3.ui.settings

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream4.compose.DeviceLayout.Companion.isAutoTv

@StringRes
fun TvType.toStringRes(): Int = when (this) {
    TvType.TvSeries -> R.string.tv_series_singular
    TvType.Anime -> R.string.anime_singular
    TvType.OVA -> R.string.ova_singular
    TvType.AnimeMovie -> R.string.movies_singular
    TvType.Cartoon -> R.string.cartoons_singular
    TvType.Documentary -> R.string.documentaries_singular
    TvType.Movie -> R.string.movies_singular
    TvType.Torrent -> R.string.torrent_singular
    TvType.AsianDrama -> R.string.asian_drama_singular
    TvType.Live -> R.string.live_singular
    TvType.Others -> R.string.other_singular
    TvType.NSFW -> R.string.nsfw_singular
    TvType.Music -> R.string.music_singular
    TvType.AudioBook -> R.string.audio_book_singular
    TvType.CustomMedia -> R.string.custom_media_singular
    TvType.Audio -> R.string.audio_singular
    TvType.Podcast -> R.string.podcast_singular
    TvType.Video -> R.string.video_singular
}

object Globals {
    var beneneCount = 0

    const val PHONE: Int = 0b00001
    const val TV: Int = 0b00010
    const val EMULATOR: Int = 0b00100
    private const val INVALID = 0
    private var layoutId = INVALID

    private fun Context.getLayoutInt(): Int {
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(this)
        return settingsManager.getInt(this.getString(R.string.app_layout_key), -1)
    }

    fun Context.updateTv() {
        layoutId = when (getLayoutInt()) {
            -1 -> if (isAutoTv(this)) TV else PHONE
            0 -> PHONE
            1 -> TV
            2 -> EMULATOR
            else -> PHONE
        }
    }

    /** Returns true if the current orientation is landscape. */
    fun isLandscape(): Boolean =
        isLayout(TV or EMULATOR) ||
                Resources.getSystem().configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Returns true if the layout is any of the flags,
     * so isLayout(TV or EMULATOR) is a valid statement for checking if the layout is in the emulator
     * or tv. Auto will become the "TV" or the "PHONE" layout.
     *
     * Valid flags are: PHONE, TV, EMULATOR
     * */
    fun isLayout(flags: Int): Boolean {
        return (layoutId and flags) != 0
    }
}
