package com.lagradost.cloudstream3.ui.settings

import androidx.compose.runtime.Immutable
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
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
    )

    private val defaultHeaders = mapOf("Accept" to "application/vnd.github.v3+json")

    @Throws
    suspend fun getShaFromTag(
        userName: String,
        repository: String,
        tag: String,
    ): String {
        return app.get(
            url = "https://api.github.com/repos/$userName/$repository/git/ref/tags/$tag",
            headers = defaultHeaders
        ).parsed<GithubTag>().githubObject.sha
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

        val latestRelease = app.get(
            url = latestReleaseUrl,
            headers = defaultHeaders
        ).parsed<GithubRelease>()

        val apkAssets = latestRelease.assets.filter { asset ->
            asset.contentType == contentType || asset.name.endsWith(".apk")
        }

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
          ?: apkAssets.first()

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
            nodeId = latestRelease.nodeId
        )
    }
}