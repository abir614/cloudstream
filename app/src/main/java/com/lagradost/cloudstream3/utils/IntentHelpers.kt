package com.lagradost.cloudstream3.utils

import android.content.Intent
import android.os.Build

inline fun <reified T> Intent.getSafeParcelableExtra(key: String): T? =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        @Suppress("DEPRECATION")
        getParcelableExtra(key) else getParcelableExtra(key, T::class.java)