package com.lagradost.cloudstream3.ui.home

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import androidx.viewbinding.ViewBinding
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.databinding.HomeRemoveGridBinding
import com.lagradost.cloudstream3.databinding.HomeRemoveGridExpandedBinding
import com.lagradost.cloudstream3.databinding.HomeResultGridBinding
import com.lagradost.cloudstream3.databinding.HomeResultGridExpandedBinding
import com.lagradost.cloudstream3.domain.engine.NsfwFilterEngine
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.newSharedPool
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.DeviceProfile
import com.lagradost.cloudstream3.utils.UIHelper.applyTvCardFocusHover
import com.lagradost.cloudstream3.utils.UIHelper.isBottomLayout
import com.lagradost.cloudstream3.utils.UIHelper.resetTvCardHover
import com.lagradost.cloudstream3.utils.UIHelper.toPx

class HomeScrollViewHolderState(view: ViewBinding) : ViewHolderState<Boolean>(view) {
    // very shitty that we cant store the state when the view clears,
    // but this is because the focus clears before the view is removed
    // so we have to manually store it
    var wasFocused: Boolean = false
    override fun save(): Boolean = wasFocused
    override fun restore(state: Boolean) {
        if (state) {
            wasFocused = false
            // only refocus if tv and the view is still active, visible, and focusable
            if (isLayout(TV) && itemView.isVisible && itemView.isFocusable) {
                try {
                    itemView.requestFocus()
                } catch (_: Throwable) {
                }
            }
        }
    }
}

class ResumeItemAdapter(
    nextFocusUp: Int? = null,
    nextFocusDown: Int? = null,
    clickCallback: (SearchClickCallback) -> Unit,
    private val removeCallback: (View) -> Unit,
) : HomeChildItemAdapter(
    id = "resumeAdapter".hashCode(),
    nextFocusUp = nextFocusUp,
    nextFocusDown = nextFocusDown,
    clickCallback = clickCallback
) {
    // As there is no popup on TV we instead use the footer to clear
    override val footers = if (isLayout(TV or EMULATOR)) 1 else 0

    override fun onCreateFooter(parent: ViewGroup): ViewHolderState<Boolean> {
        val expanded = parent.context.isBottomLayout()
        val inflater = LayoutInflater.from(parent.context)
        val binding = if (expanded) HomeRemoveGridExpandedBinding.inflate(
            inflater,
            parent,
            false
        ) else HomeRemoveGridBinding.inflate(inflater, parent, false)
        return HomeScrollViewHolderState(binding)
    }

    override fun onClearView(holder: ViewHolderState<Boolean>) {
        // Clear the image, idk if this saves ram or not, but I guess?
        clearImage(holder.view.root.findViewById(R.id.imageView))
    }

    override fun onBindFooter(holder: ViewHolderState<Boolean>) {
        this.applyBinding(holder, false)
        when (val binding = holder.view) {
            is HomeRemoveGridBinding -> {
                updateLayoutParms(binding.backgroundCard, setWidth, setHeight)
            }

            is HomeRemoveGridExpandedBinding -> {
                updateLayoutParms(binding.backgroundCard, setWidth, setHeight)
            }
        }
        holder.itemView.apply {
            if (isLayout(TV)) {
                isFocusableInTouchMode = true
                isFocusable = true
                if (!hasFocus()) {
                    resetTvCardHover()
                }
                setOnFocusChangeListener { v, hasFocus ->
                    v.applyTvCardFocusHover(hasFocus)
                }
            }
            nextFocusUp?.let {
                nextFocusUpId = it
            }
            nextFocusDown?.let {
                nextFocusDownId = it
            }

            setOnClickListener { v ->
                removeCallback.invoke(v ?: return@setOnClickListener)
            }
        }
    }
}

/** Remember to set `updatePosterSize` to cache the poster size,
 * otherwise the width and height is unset */
open class HomeChildItemAdapter(
    id: Int,
    var nextFocusUp: Int? = null,
    var nextFocusDown: Int? = null,
    var clickCallback: (SearchClickCallback) -> Unit,
) :
    BaseAdapter<SearchResponse, Boolean>(
        id, diffCallback = BaseDiffCallback(
            itemSame = { a, b ->
                a.url == b.url && a.name == b.name
            },
            contentSame = { a, b ->
                a == b
            })
    ) {
    var hasNext: Boolean = false
    private var boundFilterVersion: Int = NsfwFilterEngine.configVersion.value

    override fun submitList(list: Collection<SearchResponse>?, commitCallback: Runnable?) {
        val current = NsfwFilterEngine.configVersion.value
        val changed = boundFilterVersion != current
        if (changed) {
            boundFilterVersion = current
            notifyDataSetChanged()
        }
        super.submitList(list, Runnable {
            if (changed) {
                notifyDataSetChanged()
            }
            commitCallback?.run()
        })
    }
    var isHorizontal: Boolean = false
        set(value) {
            field = value
            updateCachedPosterSize()
        }

    private fun updateCachedPosterSize() {
        setWidth = if (!isHorizontal) {
            minPosterSize
        } else {
            maxPosterSize
        }
        setHeight = if (!isHorizontal) {
            maxPosterSize
        } else {
            minPosterSize
        }
    }

    init {
        updateCachedPosterSize()
    }

    protected var setWidth = 0
    protected var setHeight = 0

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Boolean> {
        val expanded = parent.context.isBottomLayout()
        val inflater = LayoutInflater.from(parent.context)
        val binding = if (expanded) HomeResultGridExpandedBinding.inflate(
            inflater,
            parent,
            false
        ) else HomeResultGridBinding.inflate(inflater, parent, false)
        if (DeviceProfile.hasBrokenHardwareGpu() || DeviceProfile.isUltraPotato(parent.context)) {
            val card = when (binding) {
                is HomeResultGridBinding -> binding.backgroundCard
                is HomeResultGridExpandedBinding -> binding.backgroundCard
                else -> null
            }
            card?.cardElevation = 0f
            card?.elevation = 0f
        }
        return HomeScrollViewHolderState(binding)
    }

    companion object {
        // Share views cleanly across HomeChildItemAdapters without forcing frequent re-creation on scroll
        val sharedPool =
            newSharedPool { setMaxRecycledViews(CONTENT, 64) }

        var minPosterSize: Int = 114.toPx
        var maxPosterSize: Int = 180.toPx

        fun updatePosterSize(context: Context, value: Int? = null) {
            val scale = value ?: PreferenceManager.getDefaultSharedPreferences(context)
                ?.getInt(context.getString(R.string.poster_size_key), 0) ?: 0
            // Scale by +10% per step
            val mul = 1.0f + scale * 0.1f
            if (isLayout(TV)) {
                // Size posters on TV so 6 cards fit across each section row comfortably and legibly
                val dm = context.resources.displayMetrics
                val screenWidthPx = dm.widthPixels
                val navRailWidthPx = try {
                    context.resources.getDimensionPixelSize(R.dimen.nav_rail_view_width)
                } catch (_: Throwable) {
                    62.toPx
                }
                val rowPaddingPx = 13.toPx // homepage_parent_tv paddingStart (8dp) + paddingEnd (5dp)
                val cardMarginsPx = 6 * 4.toPx // 6 cards * 2dp horizontal margin each side
                val usableWidthPx = (screenWidthPx - navRailWidthPx - rowPaddingPx - cardMarginsPx)
                    .coerceAtLeast(6 * 114.toPx)
                val baseTvWidth = usableWidthPx / 6f
                minPosterSize = (baseTvWidth * mul).toInt()
                maxPosterSize = (baseTvWidth * (180f / 114f) * mul).toInt()
            } else {
                minPosterSize = (114.toPx.toFloat() * mul).toInt()
                maxPosterSize = (180.toPx.toFloat() * mul).toInt()
            }
        }

        fun updateLayoutParms(layout: FrameLayout, width: Int, height: Int) {
            val params = layout.layoutParams
            if (params.height == height && params.width == width) return

            params.width = width
            params.height = height

            layout.layoutParams = params
        }
    }

    protected fun applyBinding(holder: ViewHolderState<Boolean>, isFirstItem: Boolean) {
        when (val binding = holder.view) {
            is HomeResultGridBinding -> {
                updateLayoutParms(binding.backgroundCard, setWidth, setHeight)
                if (isFirstItem) { // keep TV D-Pad left exit cleanly wired to nav rail
                    binding.backgroundCard.nextFocusLeftId = R.id.nav_rail_view
                } else {
                    binding.backgroundCard.nextFocusLeftId = View.NO_ID
                }
            }

            is HomeResultGridExpandedBinding -> {
                updateLayoutParms(binding.backgroundCard, setWidth, setHeight)

                if (isFirstItem) { // to fix tv
                    binding.backgroundCard.nextFocusLeftId = R.id.nav_rail_view
                } else {
                    binding.backgroundCard.nextFocusLeftId = View.NO_ID
                }
            }
        }
    }

    override fun onBindContent(
        holder: ViewHolderState<Boolean>,
        item: SearchResponse,
        position: Int
    ) {
        applyBinding(holder, position == 0)

        SearchResultBuilder.bind(
            clickCallback = { click ->
                // ok, so here we hijack the callback to fix the focus
                when (click.action) {
                    SEARCH_ACTION_LOAD -> (holder as? HomeScrollViewHolderState)?.wasFocused = true
                }
                clickCallback(click)
            },
            item,
            position,
            holder.itemView,
            nextFocusUp,
            nextFocusDown
        )

        holder.itemView.tag = position
    }
}
