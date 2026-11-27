package com.lagradost.cloudstream3.ui.account

import android.annotation.SuppressLint
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.utils.UIHelper.openActivity

class AccountSelectActivity : FragmentActivity() {

    companion object {
        var hasLoggedIn: Boolean = false
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Privacy-focused build: multi-account selection removed; launch directly into MainActivity
        navigateToMainActivity()
    }

    @SuppressLint("UnsafeIntentLaunch")
    private fun navigateToMainActivity() {
        hasLoggedIn = true
        // We want to propagate any intent we get here to MainActivity since this is just an intermediary
        openActivity(MainActivity::class.java, baseIntent = intent)
        finish() // Finish the account selection activity
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
