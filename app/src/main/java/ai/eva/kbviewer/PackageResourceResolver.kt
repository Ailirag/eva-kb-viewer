package ai.eva.kbviewer

import java.io.File
import java.net.URI

internal data class PackageResource(
    val file: File,
    val mimeType: String,
    val encoding: String?,
)

internal class PackageResourceResolver(root: File) {
    private val root = root.canonicalFile

    fun resolve(url: String): PackageResource? {
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return null
        }
        if (!uri.scheme.equals("file", ignoreCase = true)) return null
        val candidate = try {
            File(uri).canonicalFile
        } catch (_: Exception) {
            return null
        }
        val base = root.path
        if (candidate.path != base && !candidate.path.startsWith(base + File.separator)) return null
        if (!candidate.isFile) return null
        return PackageResource(candidate, mimeType(candidate), textEncoding(candidate))
    }

    private fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "js" -> "application/javascript"
        "json" -> "application/json"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    private fun textEncoding(file: File): String? = when (file.extension.lowercase()) {
        "html", "htm", "css", "js", "json", "svg" -> "utf-8"
        else -> null
    }
}
