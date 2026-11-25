package com.lagradost.cloudstream4.compose

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun LazyGridState.IsScrolling(up : Runnable, down : Runnable) {
    LaunchedEffect(this) {
        snapshotFlow { lastScrolledBackward to lastScrolledForward }
            .distinctUntilChanged()
            .collect { (backward, forward) ->
                if (backward) up.run()
                if (forward) down.run()
            }
    }
}

@Composable
fun LazyListState.IsScrolling(up : Runnable, down : Runnable) {
    LaunchedEffect(this) {
        snapshotFlow { firstVisibleItemIndex to (lastScrolledBackward to lastScrolledForward) }
            .distinctUntilChanged()
            .collect { (_, dirs) ->
                if (dirs.first) up.run()
                if (dirs.second) down.run()
            }
    }
}
