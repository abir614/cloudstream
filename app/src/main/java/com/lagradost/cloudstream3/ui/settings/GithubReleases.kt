package com.lagradost.cloudstream3.ui.settings

import androidx.compose.runtime.Immutable
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.InAppUpdater
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.Throws

object GithubReleases {
    @Serializable
    private data class GithubAsset(
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("size") @SerialName("size") val size: Int, // Size in bytes
        @JsonProperty("browser_download_url") @SerialName("browser_download_url") val browserDownloadUrl: String,
        @JsonProperty("content_type") @SerialName("content_type") val contentType: String, // application/vnd.android.package-archive
        @JsonProperty("digest") @SerialName("digest") val digest: String? = null, // sha256:..., may be null
    )

    @Serializable
    private data class GithubRelease(
        @JsonProperty("tag_name") @SerialName("tag_name") val tagName: String, // Version code
        @JsonProperty("body") @SerialName("body") val body: String, // Description
        @JsonProperty("assets") @SerialName("assets") val assets: List<GithubAsset>,
        @JsonProperty("target_commitish") @SerialName("target_commitish") val targetCommitish: String, // Branch
        @JsonProperty("prerelease") @SerialName("prerelease") val prerelease: Boolean,
        @JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
        @JsonProperty("created_at") @SerialName("created_at") val createdAt: String, // YYYY-MM-DDTHH:MM:SSZ
    )

    @Serializable
    private data class GithubObject(
        @JsonProperty("sha") @SerialName("sha") val sha: String, // SHA-256 hash
        //@JsonProperty("type") @SerialName("type") val type: String,
        ///@JsonProperty("url") @SerialName("url") val url: String,
    )

    @Serializable
    private data class GithubTag(
        //@JsonProperty("node_id") @SerialName("node_id") val nodeId: String,
        @JsonProperty("object") @SerialName("object") val githubObject: GithubObject,
    )

    /** GitHub file update package */
    @Immutable
    data class GithubFile(
        /** File digest, sha:xxx */
        val digest: String?,
        /** File url for download */
        val downloadUrl: String,
        /** Filename without the extension */
        val displayName: String,
        /** Changelog, aka the commit message */
        val changeLog: String,
        /** Name of the tag, aka unique release name like vX.X.X or pre-release */
        val tagName: String,
        /** Unique node id */
        val nodeId: String,
        /** Git commit SHA or branch target from GitHub release */
        val targetCommitish: String? = null,
    )

    private val defaultHeaders = mapOf("Accept" to "application/vnd.github.v3+json")

    @Throws
    suspend fun getShaFromTag(
        userName: String,
        repository: String,
        tag: String,
    ): String? {
        try {
            val res = app.get(
                url = "https://api.github.com/repos/$userName/$repository/git/ref/tags/$tag",
                headers = defaultHeaders
            )
            if (res.isSuccessful) {
                val sha = res.parsedSafe<GithubTag>()?.githubObject?.sha
                if (!sha.isNullOrBlank()) return sha
            }
        } catch (_: Throwable) {}

        // Fallback: If api.github.com is rate-limited, read commit SHA from CDN release.json
        try {
            val cdnRes = app.get("https://github.com/$userName/$repository/releases/download/$tag/release.json")
            if (cdnRes.isSuccessful) {
                val cdnRelease = cdnRes.parsedSafe<GithubRelease>()
                val targetSha = cdnRelease?.targetCommitish?.trim()
                if (!targetSha.isNullOrBlank() && targetSha.matches(Regex("^[0-9a-fA-F]{7,40}$"))) {
                    return targetSha
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    @Throws
    suspend fun getLatestReleaseFile(
        userName: String,
        repository: String,
        prerelease: Boolean,
        prereleaseTag: String,
        contentType: String,
    ): GithubFile? {
        val latestReleaseUrl = if (prerelease) {
            // Find the release object
            // https://docs.github.com/en/rest/releases/releases?apiVersion=2026-03-10#get-a-release-by-tag-name
            "https://api.github.com/repos/$userName/$repository/releases/tags/$prereleaseTag"
        } else {
            // Just get the latest release
            // https://docs.github.com/en/rest/releases/releases?apiVersion=2026-03-10#get-the-latest-release
            // The latest release is the most recent non-prerelease, non-draft release, sorted by the created_at attribute
            "https://api.github.com/repos/$userName/$repository/releases/latest"
        }

        var latestRelease: GithubRelease? = null

        try {
            val response = app.get(
                url = latestReleaseUrl,
                headers = defaultHeaders
            )

            if (response.isSuccessful) {
                latestRelease = response.parsedSafe<GithubRelease>()
            } else if (response.code == 403 || response.code == 429) {
                android.util.Log.w("GithubReleases", "GitHub REST API rate limit exceeded (HTTP ${response.code}). Falling back to rate-limit-free CDN release manifest.")
            } else {
                android.util.Log.w("GithubReleases", "GitHub REST API returned HTTP ${response.code}. Attempting CDN fallback.")
            }
        } catch (t: Throwable) {
            android.util.Log.w("GithubReleases", "Primary GitHub API check error: ${t.message}. Attempting CDN fallback.")
        }

        // Rate-limit proof CDN fallback: Fetches release.json directly from GitHub Release CDN (Fastly/Azure) with 0 rate limits
        if (latestRelease == null) {
            val targetTag = if (prerelease) prereleaseTag else "latest"
            val cdnUrls = listOf(
                "https://github.com/$userName/$repository/releases/download/$targetTag/release.json",
                "https://raw.githubusercontent.com/$userName/$repository/master/release.json"
            )
            for (cdnUrl in cdnUrls) {
                try {
                    val cdnResponse = app.get(cdnUrl)
                    if (cdnResponse.isSuccessful) {
                        val parsed = cdnResponse.parsedSafe<GithubRelease>()
                        if (parsed != null && parsed.assets.isNotEmpty()) {
                            android.util.Log.i("GithubReleases", "Successfully resolved release update via rate-limit-free CDN manifest: $cdnUrl")
                            latestRelease = parsed
                            break
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("GithubReleases", "CDN fallback fetch failed for $cdnUrl: ${t.message}")
                }
            }
        }

        if (latestRelease == null) {
            return null
        }

        val apkAssets = latestRelease.assets.filter { asset ->
            asset.contentType == contentType || asset.name.endsWith(".apk")
        }.sortedWith(compareByDescending { asset ->
            InAppUpdater.parseVersionScore(asset.name)
        })

        if (apkAssets.isEmpty()) {
            return null
        }

        // Intelligently select best matching APK for running device architecture
        val supportedAbis = android.os.Build.SUPPORTED_ABIS?.toList() ?: emptyList()
        val foundAsset = when {
            supportedAbis.any { it.contains("arm64") } ->
                apkAssets.firstOrNull { it.name.contains("arm64-v8a") }
            supportedAbis.any { it.contains("armeabi") } ->
                apkAssets.firstOrNull { it.name.contains("armeabi-v7a") }
            supportedAbis.any { it.contains("x86_64") } ->
                apkAssets.firstOrNull { it.name.contains("x86_64") }
            supportedAbis.any { it.contains("x86") } ->
                apkAssets.firstOrNull { it.name.contains("x86") }
            else -> null
        } ?: apkAssets.firstOrNull { it.name.contains("universal") }
          ?: apkAssets.firstOrNull() ?: return null

        val sumsAsset = latestRelease.assets.firstOrNull {
            it.name.equals("SHA256SUMS", ignoreCase = true) || it.name.equals("SHA256SUMS.txt", ignoreCase = true)
        }
        val digest = sumsAsset?.let { asset ->
            try {
                val sumsText = app.get(asset.browserDownloadUrl).text
                val hash = sumsText.lines().firstOrNull { line ->
                    line.contains(foundAsset.name)
                }?.split("""\s+""".toRegex())?.firstOrNull()?.trim()?.lowercase()
                if (!hash.isNullOrBlank()) "sha256:$hash" else null
            } catch (_: Throwable) {
                null
            }
        } ?: foundAsset.digest

        return GithubFile(
            digest = digest,
            downloadUrl = foundAsset.browserDownloadUrl,
            displayName = latestRelease.tagName,
            changeLog = latestRelease.body,
            tagName = latestRelease.tagName,
            nodeId = latestRelease.nodeId,
            targetCommitish = latestRelease.targetCommitish
        )
    }
}