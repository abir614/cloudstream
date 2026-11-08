package com.lagradost.cloudstream3.ui.search

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.view.isVisible
import androidx.palette.graphics.Palette
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.LiveSearchResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.isMovieType
import com.lagradost.cloudstream3.syncproviders.SyncAPI
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.getShortSeasonText
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.DataStoreHelper.fixVisual
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.ImageLoader.applyCensoredPoster
import com.lagradost.cloudstream3.domain.engine.NsfwFilterEngine
import com.lagradost.cloudstream3.domain.engine.NsfwFilterLevel
import com.lagradost.cloudstream3.utils.AppContextUtils.isNsfwEnabled
import com.lagradost.cloudstream3.utils.AppContextUtils.shouldCensorPosters
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.applyTvCardFocusHover
import com.lagradost.cloudstream3.utils.UIHelper.resetTvCardHover
import com.lagradost.cloudstream3.utils.getImageFromDrawable

object SearchResultBuilder {
    private class CardViewHolder(
        val cardView: ImageView,
        val cardText: TextView?,
        val textIsDub: TextView?,
        val textIsSub: TextView?,
        val textFlag: TextView?,
        val rating: TextView?,
        val textQuality: TextView?,
        val shadow: View?,
        val bg: CardView,
        val bar: ProgressBar?,
        val playImg: ImageView?,
        val episodeText: TextView?
    )

    @Volatile var cachedShowSub: Boolean = false; private set
    @Volatile var cachedShowDub: Boolean = false; private set
    @Volatile var cachedShowTitle: Boolean = false; private set
    @Volatile var cachedShowEpisodeText: Boolean = false; private set
    @Volatile var cachedShowHd: Boolean = false; private set
    @Volatile var cachedShowRatingView: Boolean = false; private set
    @Volatile private var isCacheInitialized: Boolean = false

    fun updateCache(context: Context?) {
        if (context == null) return
        val settingsManager = PreferenceManager.getDefaultSharedPreferences(context) ?: return

        cachedShowSub = settingsManager.getBoolean(context.getString(R.string.show_sub_key), false)
        cachedShowDub = settingsManager.getBoolean(context.getString(R.string.show_dub_key), false)
        cachedShowTitle = settingsManager.getBoolean(context.getString(R.string.show_title_key), false)
        cachedShowEpisodeText = settingsManager.getBoolean(context.getString(R.string.show_episode_text_key), false)
        cachedShowHd = settingsManager.getBoolean(context.getString(R.string.show_hd_key), false)
        cachedShowRatingView = settingsManager.getBoolean(context.getString(R.string.show_rating_key), false)
        isCacheInitialized = true
    }

    @SuppressLint("StringFormatInvalid")
    fun bind(
        clickCallback: (SearchClickCallback) -> Unit,
        card: SearchResponse,
        position: Int,
        itemView: View,
        nextFocusUp: Int? = null,
        nextFocusDown: Int? = null,
        colorCallback: ((Palette) -> Unit)? = null
    ) {
        if (!isCacheInitialized) {
            updateCache(itemView.context)
        }
        val vh = (itemView.getTag(R.id.card_view_holder_tag) as? CardViewHolder) ?: CardViewHolder(
            cardView = itemView.findViewById(R.id.imageView),
            cardText = itemView.findViewById(R.id.imageText),
            textIsDub = itemView.findViewById(R.id.text_is_dub),
            textIsSub = itemView.findViewById(R.id.text_is_sub),
            textFlag = itemView.findViewById(R.id.text_flag),
            rating = itemView.findViewById(R.id.text_rating),
            textQuality = itemView.findViewById(R.id.text_quality),
            shadow = itemView.findViewById(R.id.title_shadow),
            bg = itemView.findViewById(R.id.background_card),
            bar = itemView.findViewById(R.id.watchProgress),
            playImg = itemView.findViewById(R.id.search_item_download_play),
            episodeText = itemView.findViewById(R.id.episode_text)
        ).also { itemView.setTag(R.id.card_view_holder_tag, it) }

        val cardView = vh.cardView
        val cardText = vh.cardText
        val textIsDub = vh.textIsDub
        val textIsSub = vh.textIsSub
        val textFlag = vh.textFlag
        val rating = vh.rating
        val textQuality = vh.textQuality
        val shadow = vh.shadow
        val bg = vh.bg
        val bar = vh.bar
        val playImg = vh.playImg
        val episodeText = vh.episodeText

        // Do logic

        bar?.isVisible = false
        playImg?.isVisible = false
        textIsDub?.isVisible = false
        textIsSub?.isVisible = false
        textFlag?.isVisible = false
        rating?.isVisible = false
        episodeText?.isVisible = false

        val showSub = cachedShowSub
        val showDub = cachedShowDub
        val showTitle = cachedShowTitle
        val showEpisodeText = cachedShowEpisodeText
        val showHd = cachedShowHd
        val showRatingView = cachedShowRatingView
        if (card is SyncAPI.LibraryItem) {
            val ratingText = card.personalRating?.toStringNull(0.1, 10, 1)
            val showRating = !ratingText.isNullOrBlank()
            rating?.isVisible = showRating
            if (showRating) {
                rating?.text = ratingText
            }
        } else if (showRatingView) {
            val ratingText = card.score?.toStringNull(0.1, 10, 1)
            val showRating = !ratingText.isNullOrBlank()
            rating?.isVisible = showRating
            if (showRating) {
                rating?.text = ratingText
            }
        }

        shadow?.isVisible = showTitle

        when (card.quality) {
            SearchQuality.BlueRay -> R.string.quality_blueray
            SearchQuality.Cam -> R.string.quality_cam
            SearchQuality.CamRip -> R.string.quality_cam_rip
            SearchQuality.DVD -> R.string.quality_dvd
            SearchQuality.HD -> R.string.quality_hd
            SearchQuality.HQ -> R.string.quality_hq
            SearchQuality.HdCam -> R.string.quality_cam_hd
            SearchQuality.Telecine -> R.string.quality_tc
            SearchQuality.Telesync -> R.string.quality_ts
            SearchQuality.WorkPrint -> R.string.quality_workprint
            SearchQuality.SD -> R.string.quality_sd
            SearchQuality.FourK -> R.string.quality_4k
            SearchQuality.UHD -> R.string.quality_uhd
            SearchQuality.SDR -> R.string.quality_sdr
            SearchQuality.HDR -> R.string.quality_hdr
            SearchQuality.WebRip -> R.string.quality_webrip
            null -> null
        }?.let { textRes ->
            textQuality?.setText(textRes)
            textQuality?.isVisible = showHd
        } ?: run {
            textQuality?.isVisible = false
        }

        val context = cardView.context
        val isAdultAllowed = context.isNsfwEnabled()
        val filterLevel = NsfwFilterEngine.getFilterLevel(context)
        val isCardNsfw = NsfwFilterEngine.isNsfw(card, filterLevel)
        val isTv = isLayout(TV)

        fun hideCardItem() {
            cardView.setImageDrawable(null)
            cardView.setTag(R.id.coil_image_loaded_tag, null)
            bg.isVisible = false
            cardText?.isVisible = false
            shadow?.isVisible = false
            itemView.setOnClickListener(null)
            itemView.setOnLongClickListener(null)
            itemView.onFocusChangeListener = null

            if (isTv) {
                // On TV: Keep grid cell geometry intact so Leanback D-pad focus navigation never crashes
                cardView.setImageResource(R.drawable.default_cover)
                if (!itemView.hasFocus()) {
                    itemView.isFocusable = false
                    itemView.isFocusableInTouchMode = false
                    itemView.isClickable = false
                }
            } else {
                itemView.isVisible = false
                itemView.isFocusable = false
                itemView.isFocusableInTouchMode = false
                itemView.isClickable = false
            }
        }

        if (!isAdultAllowed && isCardNsfw) {
            hideCardItem()
            return
        }

        itemView.isVisible = true
        itemView.isClickable = true
        itemView.isFocusable = true
        itemView.isFocusableInTouchMode = true
        bg.isVisible = true
        cardText?.isVisible = showTitle
        shadow?.isVisible = true
        val lp = itemView.layoutParams
        if (lp != null && (lp.width == 0 || lp.height == 0)) {
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            itemView.layoutParams = lp
        }
        cardView.setTag(R.id.parent_view_tag, java.lang.ref.WeakReference(itemView))
        cardText?.text = card.name
        cardText?.isVisible = showTitle
        cardView.isVisible = true

        val shouldCensor = isAdultAllowed && shouldCensorPosters(context) && isCardNsfw
        if (shouldCensor) {
            applyCensoredPoster(cardView, card.posterUrl)
        } else if (!card.posterUrl.isNullOrEmpty()) {
            cardView.loadImage(card.posterUrl, card.posterHeaders) {
                placeholder { getImageFromDrawable(itemView.context, R.drawable.default_cover) }
                error { getImageFromDrawable(itemView.context, R.drawable.default_cover) }
            }
        } else {
            if (!isAdultAllowed && filterLevel == NsfwFilterLevel.EXTREME) {
                hideCardItem()
                return
            } else {
                cardView.loadImage(R.drawable.default_cover)
            }
        }

        fun click(view: View?) {
            clickCallback.invoke(
                SearchClickCallback(
                    if (card is DataStoreHelper.ResumeWatchingResult) SEARCH_ACTION_PLAY_FILE else SEARCH_ACTION_LOAD,
                    view ?: return,
                    position,
                    card
                )
            )
        }

        fun longClick(view: View?) {
            clickCallback.invoke(
                SearchClickCallback(
                    SEARCH_ACTION_SHOW_METADATA,
                    view ?: return,
                    position,
                    card
                )
            )
        }

        fun focus(view: View?, focus: Boolean) {
            if (focus) {
                clickCallback.invoke(
                    SearchClickCallback(
                        SEARCH_ACTION_FOCUSED,
                        view ?: return,
                        position,
                        card
                    )
                )
            }
        }

        bg.isFocusable = false
        bg.isFocusableInTouchMode = false
        if (!isLayout(TV)) {
            bg.setOnClickListener {
                click(it)
            }
            bg.setOnLongClickListener {
                longClick(it)
                return@setOnLongClickListener true
            }
        }

        itemView.setOnClickListener {
            click(it)
        }
        if (nextFocusUp != null) {
            itemView.nextFocusUpId = nextFocusUp
        }

        if (nextFocusDown != null) {
            itemView.nextFocusDownId = nextFocusDown
        }

        if (isLayout(TV)) {
            itemView.isFocusableInTouchMode = true
            itemView.isFocusable = true
            if (!itemView.hasFocus()) {
                itemView.resetTvCardHover()
            }
        }

        itemView.setOnLongClickListener {
            longClick(it)
            return@setOnLongClickListener true
        }

        itemView.setOnFocusChangeListener { view, b ->
            if (isLayout(TV)) {
                view.applyTvCardFocusHover(b)
            }
            focus(view, b)
        }

        when (card) {
            is LiveSearchResponse -> {
                SubtitleHelper.getFlagFromIso(card.lang)?.let { flagEmoji ->
                    textFlag?.apply {
                        isVisible = true
                        text = flagEmoji
                    }
                }
            }

            is DataStoreHelper.ResumeWatchingResult -> {
                val pos = card.watchPos?.fixVisual()
                if (pos != null) {
                    bar?.max = (pos.duration / 1000).toInt()
                    bar?.progress = (pos.position / 1000).toInt()
                    bar?.visibility = View.VISIBLE
                }
                playImg?.visibility = View.VISIBLE
                if (card.type?.isMovieType() == false && showEpisodeText) {
                    episodeText?.context?.getShortSeasonText(card.episode, card.season)?.let {text->
                        episodeText.text = text
                        episodeText.isVisible = true
                    }
                }
            }

            is AnimeSearchResponse -> {
                val dubStatus = card.dubStatus
                if (!dubStatus.isNullOrEmpty()) {
                    if (dubStatus.contains(DubStatus.Dubbed)) {
                        textIsDub?.isVisible = showDub
                    }
                    if (dubStatus.contains(DubStatus.Subbed)) {
                        textIsSub?.isVisible = showSub
                    }
                }

                val dubEpisodes = card.episodes[DubStatus.Dubbed]
                val subEpisodes = card.episodes[DubStatus.Subbed]

                textIsDub?.apply {
                    val dubText = context.getString(R.string.app_dubbed_text)
                    text = if (dubEpisodes != null && dubEpisodes > 0) {
                        context.getString(R.string.app_dub_sub_episode_text_format)
                            .format(dubText, dubEpisodes)
                    } else {
                        dubText
                    }
                }

                textIsSub?.apply {
                    val subText = context.getString(R.string.app_subbed_text)
                    text = if (subEpisodes != null && subEpisodes > 0) {
                        context.getString(R.string.app_dub_sub_episode_text_format)
                            .format(subText, subEpisodes)
                    } else {
                        subText
                    }
                }
            }
        }

        // Zero-allocation rounded corner styling for badges (ordering: textIsDub -> textIsSub -> rating)
        val dubVis = textIsDub?.isVisible == true
        val subVis = textIsSub?.isVisible == true
        val ratingVis = rating?.isVisible == true
        val badgeCount = (if (dubVis) 1 else 0) + (if (subVis) 1 else 0) + (if (ratingVis) 1 else 0)

        when (badgeCount) {
            1 -> {
                when {
                    dubVis -> textIsDub?.setBackgroundResource(R.drawable.bg_color_both)
                    subVis -> textIsSub?.setBackgroundResource(R.drawable.bg_color_both)
                    ratingVis -> rating?.setBackgroundResource(R.drawable.bg_color_both)
                }
            }
            2 -> {
                when {
                    dubVis && subVis -> {
                        textIsDub?.setBackgroundResource(R.drawable.bg_color_top)
                        textIsSub?.setBackgroundResource(R.drawable.bg_color_bottom)
                    }
                    dubVis && ratingVis -> {
                        textIsDub?.setBackgroundResource(R.drawable.bg_color_top)
                        rating?.setBackgroundResource(R.drawable.bg_color_bottom)
                    }
                    subVis && ratingVis -> {
                        textIsSub?.setBackgroundResource(R.drawable.bg_color_top)
                        rating?.setBackgroundResource(R.drawable.bg_color_bottom)
                    }
                }
            }
            3 -> {
                textIsDub?.setBackgroundResource(R.drawable.bg_color_top)
                textIsSub?.setBackgroundResource(R.drawable.bg_color_center)
                rating?.setBackgroundResource(R.drawable.bg_color_bottom)
            }
        }
        textIsDub?.apply {
            backgroundTintList = ColorStateList.valueOf(context.colorFromAttribute(R.attr.textColor))
        }
    }
}
