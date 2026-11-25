package com.lagradost.cloudstream3.ui

import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.fragment.app.FragmentActivity
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.databinding.FragmentWebviewBinding
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppContextUtils.loadRepository

class WebviewFragment : BaseFragment<FragmentWebviewBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentWebviewBinding::inflate)
) {

    override fun fixLayout(view: View) = Unit

    override fun onBindingCreated(binding: FragmentWebviewBinding) {
        val url = arguments?.getString(WEBVIEW_URL)?.takeIf { it.isNotBlank() } ?: run {
            findNavController().popBackStack()
            return
        }

        val initialScheme = Uri.parse(url)?.scheme?.lowercase()
        if (initialScheme != "http" && initialScheme != "https") {
            findNavController().popBackStack()
            return
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val requestUrl = request?.url.toString()
                // Only allow http/https navigation; block file://, javascript:, data: etc.
                val scheme = request?.url?.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    return true // block non-http(s) navigation silently
                }
                val performedAction = MainActivity.handleAppIntentUrl(activity, requestUrl, true)
                if (performedAction) {
                    findNavController().popBackStack()
                    return true
                }

                return super.shouldOverrideUrlLoading(view, request)
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                // Cancel SSL errors — do NOT proceed with broken certificate chains
                handler?.cancel()
            }
        }

        binding.webView.apply {
            WebViewResolver.webViewUserAgent = settings.userAgentString

            addJavascriptInterface(RepoApi(activity), "RepoApi")
            settings.javaScriptEnabled = true
            settings.userAgentString = USER_AGENT
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false

            loadUrl(url)
        }
    }

    override fun onDestroyView() {
        binding?.webView?.let { webView ->
            try {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.clearHistory()
                webView.removeAllViews()
            } catch (_: Throwable) {
            } finally {
                try {
                    webView.destroy()
                } catch (_: Throwable) {
                }
            }
        }
        super.onDestroyView()
    }

    companion object {
        private const val WEBVIEW_URL = "webview_url"
        fun newInstance(webViewUrl: String) =
            Bundle().apply {
                putString(WEBVIEW_URL, webViewUrl)
            }
    }

    private class RepoApi(val activity: FragmentActivity?) {
        @JavascriptInterface
        fun installRepo(repoUrl: String) {
            activity?.loadRepository(repoUrl)
        }
    }
}
