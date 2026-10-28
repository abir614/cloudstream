package com.lagradost.cloudstream3.ui.home

import android.view.View
import androidx.viewpager2.widget.ViewPager2

class HomeScrollTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        val padding = (-position * page.width / 2).toInt()
        page.setPadding(
            padding, 0,
            -padding, 0
        )
    }
}