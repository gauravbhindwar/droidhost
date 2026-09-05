package com.droidhost.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the cloudflared binary for ARM64 Linux from the official
 * Cloudflare GitHub releases and installs it into the app's private
 * filesDir so the app can run it directly without root or external storage.
 *
 * All tokens and credentials remain ON-DEVICE only (SharedPreferences / filesDir).
 */
object CloudflaredDownloader {

    private const val TAG = "CloudflaredDownloader"
    private const val RELEASES_API =
        "https://api.github.com/repos/cloudflare/cloudflared/releases/latest"
    private const val FALLBACK_URL =
        "https://github.com/cloudflare/cloudflared/releases/download/2026.8.3/cloudflared-linux-arm64"

    data class DownloadResult(
        val success: Boolean,
        val binaryPath: String? = null,
        val version: String? = null,
        val errorMessage: String? = null
    )

    /**
     * Resolves the latest ARM64 download URL from the GitHub Releases API.
     * Falls back to the known 2026.8.3 release if the API is unreachable.
     */
    private suspend fun resolveDownloadUrl(): Pair<String, String> = withContext(Dispatchers.IO) {
        try {
            val conn = URL(RELEASES_API).openConnection() as HttpURLConnection
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
            conn.setRequestProperty("User-Agent", "DroidHost/1.0")
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            // Parse tag_name
            val versionMatch = Regex("\"tag_name\":\\s*\"([^\"]+)\"").find(body)
            val version = versionMatch?.groupValues?.get(1) ?: "2026.8.3"

            // Find linux-arm64 URL (not .deb, not .pkg, not darwin)
            val urlMatch = Regex("\"browser_download_url\":\\s*\"(https://[^\"]+cloudflared-linux-arm64)\"").find(body)
            val downloadUrl = urlMatch?.groupValues?.get(1)
                ?: "https://github.com/cloudflare/cloudflared/releases/download/$version/cloudflared-linux-arm64"

            Log.i(TAG, "Resolved version=$version url=$downloadUrl")
            Pair(downloadUrl, version)
        } catch (e: Exception) {
            Log.w(TAG, "Releases API failed, using fallback: ${e.message}")
            Pair(FALLBACK_URL, "2026.8.3")
        }
    }

    /**
     * Opens an HttpURLConnection following redirects (HTTP 301, 302, 307, 308) across hosts.
     */
    private fun openConnectionWithRedirects(initialUrl: String, timeoutMs: Int = 15_000): HttpURLConnection {
        var currentUrl = initialUrl
        var redirects = 0
        while (redirects < 6) {
            val conn = URL(currentUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "DroidHost/1.0")
            val code = conn.responseCode
            if (code in 300..399) {
                val newUrl = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                currentUrl = newUrl
                redirects++
            } else {
                return conn
            }
        }
        val fallback = URL(currentUrl).openConnection() as HttpURLConnection
        fallback.instanceFollowRedirects = true
        return fallback
    }

    /**
     * Checks if a binary was pushed via ADB to /data/local/tmp and imports it if valid.
     */
    fun importFromLocalTmp(context: Context): Boolean {
        val candidates = listOf(
            File("/data/local/tmp/cloudflared"),
            File("/data/local/tmp/cloudflared-arm64")
        )
        val destFile = File(context.filesDir, "cloudflared")
        for (src in candidates) {
            if (src.exists() && src.canRead() && src.length() > 5_000_000) {
                try {
                    src.copyTo(destFile, overwrite = true)
                    destFile.setExecutable(true, false)
                    destFile.setReadable(true, false)
                    Log.i(TAG, "Imported cloudflared from ${src.absolutePath} (${destFile.length()} bytes)")
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed importing from ${src.absolutePath}: ${e.message}")
                }
            }
        }
        return false
    }

    /**
     * Main entry point. Downloads the cloudflared ARM64 binary into
     * [context.filesDir]/cloudflared and marks it executable.
     *
     * @param onProgress Called with (bytesDownloaded, totalBytes, message).
     *                   totalBytes may be -1 if content-length is unknown.
     */
    suspend fun downloadAndInstall(
        context: Context,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, message: String) -> Unit = { _, _, _ -> }
    ): DownloadResult = withContext(Dispatchers.IO) {
        val destFile = File(context.filesDir, "cloudflared")

        // First check if ADB already pushed the binary
        if (importFromLocalTmp(context)) {
            val version = getInstalledVersion(context) ?: "2026.8.3"
            onProgress(destFile.length(), destFile.length(), "✅ Installed from local storage ($version)")
            return@withContext DownloadResult(true, binaryPath = destFile.absolutePath, version = version)
        }

        try {
            onProgress(0, -1, "Resolving latest release…")
            val (downloadUrl, version) = resolveDownloadUrl()

            onProgress(0, -1, "Connecting to GitHub releases…")
            val conn = openConnectionWithRedirects(downloadUrl)

            val responseCode = conn.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val msg = "HTTP $responseCode from $downloadUrl"
                Log.e(TAG, msg)
                return@withContext DownloadResult(false, errorMessage = msg)
            }

            val totalBytes = conn.contentLengthLong   // -1 if unknown
            onProgress(0, totalBytes, "Downloading cloudflared $version for ARM64…")

            val tmpFile = File(context.filesDir, "cloudflared.tmp")
            tmpFile.delete()

            var downloaded = 0L
            conn.inputStream.use { input ->
                tmpFile.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        downloaded += n
                        val pct = if (totalBytes > 0) (downloaded * 100 / totalBytes).toInt() else -1
                        val kbDl = downloaded / 1024
                        val msg = if (pct >= 0) {
                            "Downloading… $pct%  (${kbDl} KB / ${totalBytes / 1024} KB)"
                        } else {
                            "Downloading… ${kbDl} KB"
                        }
                        onProgress(downloaded, totalBytes, msg)
                    }
                }
            }
            conn.disconnect()

            onProgress(downloaded, totalBytes, "Installing binary…")

            // Atomically move tmp → final
            destFile.delete()
            if (!tmpFile.renameTo(destFile)) {
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
            }
            destFile.setExecutable(true, false)
            destFile.setReadable(true, false)

            val sizeMb = "%.2f".format(destFile.length().toDouble() / (1024 * 1024))
            val detectedVer = getInstalledVersion(context) ?: version
            onProgress(downloaded, totalBytes, "✅ cloudflared $detectedVer installed ($sizeMb MB)")
            Log.i(TAG, "cloudflared installed at ${destFile.absolutePath} ($sizeMb MB)")

            DownloadResult(
                success = true,
                binaryPath = destFile.absolutePath,
                version = detectedVer
            )
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            destFile.delete()
            DownloadResult(false, errorMessage = e.message ?: "Unknown error")
        }
    }

    /**
     * Executes the binary to inspect its version string.
     */
    fun getInstalledVersion(context: Context): String? {
        val f = getInstalledBinary(context) ?: return null
        return try {
            val p = Runtime.getRuntime().exec(arrayOf(f.absolutePath, "version"))
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor()
            val match = Regex("""cloudflared version (\S+)""").find(out)
            match?.groupValues?.get(1) ?: out.take(30)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns the installed cloudflared binary if it exists and is executable.
     */
    fun getInstalledBinary(context: Context): File? {
        val f = File(context.filesDir, "cloudflared")
        if (f.exists() && f.canExecute()) return f
        // Try importing if available
        if (importFromLocalTmp(context)) {
            if (f.exists() && f.canExecute()) return f
        }
        return null
    }

    /** True if a cloudflared binary is already installed. */
    fun isInstalled(context: Context): Boolean = getInstalledBinary(context) != null

    /**
     * Deletes the installed binary (for "Remove" / re-install flows).
     */
    fun uninstall(context: Context) {
        File(context.filesDir, "cloudflared").delete()
    }
}
