package com.lagradost.cloudstream3.ui.home

import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.HomepageParentBinding
import com.lagradost.cloudstream3.domain.engine.NsfwFilterEngine
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.BaseAdapter
import com.lagradost.cloudstream3.ui.BaseDiffCallback
import com.lagradost.cloudstream3.ui.ViewHolderState
import com.lagradost.cloudstream3.ui.newSharedPool
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.PHONE
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.isRecyclerScrollable
import com.lagradost.cloudstream3.utils.DeviceProfile

class LoadClickCallback(
    val action: Int = 0,
    val view: View,
    val position: Int,
    val response: LoadResponse
)

open class ParentItemAdapter(
    id: Int,
    private val clickCallback: (SearchClickCallback) -> Unit,
    private val moreInfoClickCallback: (HomeViewModel.ExpandableHomepageList) -> Unit,
    private val expandCallback: ((String) -> Unit)? = null,
) : BaseAdapter<HomeViewModel.ExpandableHomepageList, Bundle>(
    id,
    diffCallback = BaseDiffCallback(
        itemSame = { a, b -> a.list.name == b.list.name },
        contentSame = { a, b ->
            a.list.list == b.list.list
        })
) {
    companion object {
        val sharedPool =
            newSharedPool { setMaxRecycledViews(CONTENT, 8) }
    }

    data class ParentItemHolder(val binding: ViewBinding) : ViewHolderState<Bundle>(binding) {
        override fun save(): Bundle = Bundle().apply {
            val recyclerView = (binding as? HomepageParentBinding)?.homeChildRecyclerview
            putParcelable(
                "value",
                recyclerView?.layoutManager?.onSaveInstanceState()
            )
            (recyclerView?.adapter as? BaseAdapter<*, *>)?.save(recyclerView)
        }

        override fun restore(state: Bundle) {
            (binding as? HomepageParentBinding)?.homeChildRecyclerview?.layoutManager?.onRestoreInstanceState(
                state.getSafeParcelable<Parcelable>("value")
            )
        }
    }

    private var boundFilterVersion: Int = NsfwFilterEngine.configVersion.value

    fun syncFilterVersion(): Boolean {
        val current = NsfwFilterEngine.configVersion.value
        if (boundFilterVersion != current) {
            boundFilterVersion = current
            notifyDataSetChanged()
            return true
        }
        return false
    }

    override fun submitList(
        list: Collection<HomeViewModel.ExpandableHomepageList>?,
        commitCallback: Runnable?
    ) {
        val filterChanged = syncFilterVersion()
        super.submitList(list?.sortedBy { it.list.list.isEmpty() }, Runnable {
            if (filterChanged) {
                notifyDataSetChanged()
            }
            commitCallback?.run()
        })
    }

    override fun onUpdateContent(
        holder: ViewHolderState<Bundle>,
        item: HomeViewModel.ExpandableHomepageList,
        position: Int
    ) {
        val binding = holder.view
        if (binding !is HomepageParentBinding) return
        (binding.homeChildRecyclerview.adapter as? HomeChildItemAdapter)?.submitList(item.list.list)
    }

    override fun onBindContent(
        holder: ViewHolderState<Bundle>,
        item: HomeViewModel.ExpandableHomepageList,
        position: Int
    ) {
        val startFocus = R.id.nav_rail_view
        val endFocus = FOCUS_SELF
        val binding = holder.view
        if (binding !is HomepageParentBinding) return
        val info = item.list
        binding.apply {
            if (homeChildRecyclerview.layoutManager !is com.lagradost.cloudstream3.ui.result.LinearListLayout) {
                homeChildRecyclerview.setLinearListLayout(
                    isHorizontal = true,
                    nextLeft = startFocus,
                    nextRight = endFocus,
                )
            }

            val currentAdapter = homeChildRecyclerview.adapter as? HomeChildItemAdapter
            if (currentAdapter == null) {
                homeChildRecyclerview.setRecycledViewPool(HomeChildItemAdapter.sharedPool)
                homeChildRecyclerview.setHasFixedSize(true)
                homeChildRecyclerview.setItemViewCacheSize(DeviceProfile.getItemViewCacheSize(holder.itemView.context))
                homeChildRecyclerview.adapter = HomeChildItemAdapter(
                    id = id + position + 100,
                    clickCallback = clickCallback,
                    nextFocusUp = homeChildRecyclerview.nextFocusUpId,
                    nextFocusDown = homeChildRecyclerview.nextFocusDownId,
                ).apply {
                    isHorizontal = info.isHorizontalImages
                    hasNext = item.hasNext
                    submitList(item.list.list)
                }
            } else {
                currentAdapter.apply {
                    isHorizontal = info.isHorizontalImages
                    hasNext = item.hasNext
                    this.clickCallback = this@ParentItemAdapter.clickCallback
                    nextFocusUp = homeChildRecyclerview.nextFocusUpId
                    nextFocusDown = homeChildRecyclerview.nextFocusDownId
                    submitList(item.list.list)
                }
            }

            homeChildMoreInfo.text = info.name

            homeChildRecyclerview.clearOnScrollListeners()
            homeChildRecyclerview.addOnScrollListener(object :
                RecyclerView.OnScrollListener() {
                var expandCount = 0
                val name = item.list.name

                override fun onScrollStateChanged(
                    recyclerView: RecyclerView,
                    newState: Int
                ) {
                    super.onScrollStateChanged(recyclerView, newState)

                    val adapter = recyclerView.adapter
                    if (adapter !is HomeChildItemAdapter) return

                    val count = adapter.itemCount
                    val hasNext = adapter.hasNext
                    if (!recyclerView.isRecyclerScrollable() && hasNext && expandCount != count) {
                        expandCount = count
                        expandCallback?.invoke(name)
                    }
                }
            })

            //(recyclerView.adapter as HomeChildItemAdapter).notifyDataSetChanged()
            if (isLayout(PHONE or EMULATOR)) {
                homeChildMoreInfo.setOnClickListener {
                    moreInfoClickCallback.invoke(item)
                }
            }
        }
    }

    override fun onCreateContent(parent: ViewGroup): ParentItemHolder {
        val layoutResId = when {
            isLayout(TV) -> R.layout.homepage_parent_tv
            isLayout(EMULATOR) -> R.layout.homepage_parent_emulator
            else -> R.layout.homepage_parent
        }

        val inflater = LayoutInflater.from(parent.context)
        val binding = try {
            HomepageParentBinding.bind(inflater.inflate(layoutResId, parent, false))
        } catch (t: Throwable) {
            logError(t)
            // just in case someone forgot we don't want to crash
            HomepageParentBinding.inflate(inflater)
        }

        return ParentItemHolder(binding)
    }
}

@Suppress("DEPRECATION")
inline fun <reified T> Bundle.getSafeParcelable(key: String): T? =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) getParcelable(key)
    else getParcelable(key, T::class.java)