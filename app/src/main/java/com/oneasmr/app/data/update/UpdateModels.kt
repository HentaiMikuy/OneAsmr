package com.oneasmr.app.data.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A GitHub release asset (our APK). */
@Serializable
data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
    val size: Long = 0L,
)

/** Minimal slice of the GitHub "latest release" payload we consume. */
@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val name: String = "",
    val body: String? = null,
    val assets: List<GithubAsset> = emptyList(),
)

/** A concrete pending update offered to the user. */
data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val changelog: String,
)

/** Outcome of one update check, distinct enough for the UI to explain itself. */
sealed interface UpdateCheckResult {
    data class NewVersion(val info: UpdateInfo) : UpdateCheckResult
    data object UpToDate : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult
}

/**
 * Lightweight semantic version for comparing app versions. Understands
 * "major.minor[.patch]" tags (optionally v-prefixed); anything unparseable
 * is null and never compares as an update.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        private val REGEX = Regex("""^v?(\d+)\.(\d+)(?:\.(\d+))?(?:[-+].*)?$""")

        fun parse(raw: String): AppVersion? {
            val m = REGEX.matchEntire(raw.trim()) ?: return null
            return AppVersion(
                major = m.groupValues[1].toInt(),
                minor = m.groupValues[2].toInt(),
                patch = m.groupValues.getOrNull(3)?.takeIf { it.isNotEmpty() }?.toInt() ?: 0,
            )
        }
    }
}
