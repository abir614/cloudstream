package com.lagradost.cloudstream3.ui.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.HomeScrollViewBinding
import com.lagradost.cloudstream3.databinding.HomeScrollViewTvBinding
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.NoStateAdapter
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.result.ResultFragment.bindLogo
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.html
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.ImageLoader.applyCensoredPoster
import com.lagradost.cloudstream3.domain.engine.NsfwFilterEngine
import com.lagradost.cloudstream3.domain.engine.NsfwFilterLevel
import com.lagradost.cloudstream3.utils.AppContextUtils.isNsfwEnabled
import com.lagradost.cloudstream3.utils.AppContextUtils.isNsfwGloballyEnabled
import com.lagradost.cloudstream3.utils.AppContextUtils.shouldCensorPosters
import com.lagradost.cloudstream3.utils.getImageFromDrawable

class HomeScrollAdapter(
    val callback: ((View, Int, LoadResponse) -> Unit)
) : NoStateAdapter<LoadResponse>(diffCallback = BaseDiffCallback(itemSame = { a, b ->
    a.uniqueUrl == b.uniqueUrl && a.name == b.name
})) {
    var hasMoreItems: Boolean = false

    override fun onCreateContent(parent: ViewGroup): ViewHolderState<Any> {
        val inflater = LayoutInflater.from(parent.context)
        val binding = if (isLayout(TV or EMULATOR)) {
            HomeScrollViewTvBinding.inflate(inflater, parent, false)
        } else {
            HomeScrollViewBinding.inflate(inflater, parent, false)
        }

        return ViewHolderState(binding)
    }

    override fun onClearView(holder: ViewHolderState<Any>) {
        when (val binding = holder.view) {
            is HomeScrollViewBinding -> {
                clearImage(binding.homeScrollPreview)
            }

            is HomeScrollViewTvBinding -> {
                clearImage(binding.homeScrollPreview)
            }
        }
    }

    override fun onBindContent(
        holder: ViewHolderState<Any>,
        item: LoadResponse,
        position: Int,
    ) {
        val binding = holder.view
        val context = holder.itemView.context
        val isAdultAllowed = context.isNsfwEnabled()
        val filterLevel = NsfwFilterEngine.getFilterLevel(context)
        val isNsfw = NsfwFilterEngine.isNsfw(item, filterLevel)

        val posterUrl = item.backgroundPosterUrl ?: item.posterUrl
        if (!isAdultAllowed) {
            val hasInvalidPoster = filterLevel == NsfwFilterLevel.EXTREME && (
                posterUrl.isNullOrBlank() ||
                posterUrl == "null" ||
                (!posterUrl.startsWith("http://") && !posterUrl.startsWith("https://"))
            )
            if (isNsfw || hasInvalidPoster) {
                // ViewPager2 strictly enforces MATCH_PARENT on its child pages.
                // Do NOT set 0x0 layoutParams, which throws java.lang.IllegalStateException: Pages must fill the whole ViewPager2 (use match_parent).
                // Safely blank out the carousel page views instead.
                when (binding) {
                    is HomeScrollViewBinding -> {
                        clearImage(binding.homeScrollPreview)
                        binding.homeScrollPreview.setImageResource(R.drawable.default_cover)
                        binding.homeScrollPreviewTags.isVisible = false
                        binding.homeScrollPreviewTitle.text = ""
                        binding.homePreviewLogo.isVisible = false
                    }
                    is HomeScrollViewTvBinding -> {
                        clearImage(binding.homeScrollPreview)
                        binding.homeScrollPreview.setImageResource(R.drawable.default_cover)
                    }
                }
                val lp = holder.itemView.layoutParams
                if (lp != null && (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    holder.itemView.layoutParams = lp
                }
                return
            }
        }

        val lp = holder.itemView.layoutParams
        if (lp != null && (lp.width != ViewGroup.LayoutParams.MATCH_PARENT || lp.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            holder.itemView.layoutParams = lp
        }

        holder.itemView.isVisible = true
        val shouldCensor = isAdultAllowed && shouldCensorPosters(context) && isNsfw
        binding.root.findViewById<android.widget.ImageView>(R.id.home_scroll_preview)?.setTag(R.id.parent_view_tag, java.lang.ref.WeakReference(holder.itemView))

        when (binding) {
            is HomeScrollViewBinding -> {
                if (shouldCensor) {
                    applyCensoredPoster(binding.homeScrollPreview)
                } else {
                    binding.homeScrollPreview.loadImage(posterUrl, item.posterHeaders) {
                        placeholder { getImageFromDrawable(context, R.drawable.default_cover) }
                        error { getImageFromDrawable(context, R.drawable.default_cover) }
                    }
                }
                binding.homeScrollPreviewTags.apply {
                    text = item.tags?.joinToString(" • ") ?: ""
                    isGone = item.tags.isNullOrEmpty()
                    maxLines = 2
                }
                binding.homeScrollPreviewTitle.text = item.name.html()

                bindLogo(
                    url = item.logoUrl,
                    headers = item.posterHeaders,
                    titleView = binding.homeScrollPreviewTitle,
                    logoView = binding.homePreviewLogo
                )
            }

            is HomeScrollViewTvBinding -> {
                binding.homeScrollPreview.isFocusable = false
                binding.homeScrollPreview.setOnClickListener { view ->
                    callback.invoke(view ?: return@setOnClickListener, position, item)
                }
                if (shouldCensor) {
                    applyCensoredPoster(binding.homeScrollPreview)
                } else {
                    binding.homeScrollPreview.loadImage(posterUrl, item.posterHeaders) {
                        placeholder { getImageFromDrawable(context, R.drawable.default_cover) }
                        error { getImageFromDrawable(context, R.drawable.default_cover) }
                    }
                }
            }
        }
    }
}
