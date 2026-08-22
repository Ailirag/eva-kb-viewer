package ai.eva.kbviewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Показывает index.html пакета в WebView.
 *
 * Правила безопасности:
 * - JavaScript включён (без него не работают графики);
 * - разрешение INTERNET не запрашивается вовсе, сеть дополнительно блокируется
 *   настройками WebView и перехватом запросов;
 * - доступ к файлам разрешён только внутри распакованного пакета;
 * - JS-мостов (addJavascriptInterface) нет;
 * - внешняя ссылка уходит в системный браузер только после явного нажатия.
 */
class ViewerActivity : Activity() {

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val rootPath = intent?.getStringExtra(EXTRA_ROOT)
        val indexPath = intent?.getStringExtra(EXTRA_INDEX)
        if (rootPath.isNullOrEmpty() || indexPath.isNullOrEmpty()) {
            finish()
            return
        }

        setContentView(R.layout.activity_viewer)
        title = intent?.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.app_name)

        val root = try {
            File(rootPath).canonicalFile
        } catch (e: Exception) {
            finish()
            return
        }

        val view: WebView = findViewById(R.id.web_view)
        webView = view
        harden(view, root)

        if (savedInstanceState == null) {
            view.loadUrl(Uri.fromFile(File(indexPath)).toString())
        } else {
            view.restoreState(savedInstanceState)
        }
    }

    private fun harden(view: WebView, root: File) {
        view.settings.apply {
            javaScriptEnabled = ViewerWebPolicy.JAVA_SCRIPT_ENABLED
            domStorageEnabled = ViewerWebPolicy.DOM_STORAGE_ENABLED

            // Сеть: пакет обязан быть автономным.
            blockNetworkLoads = ViewerWebPolicy.BLOCK_NETWORK_LOADS
            blockNetworkImage = ViewerWebPolicy.BLOCK_NETWORK_IMAGE
            cacheMode = WebSettings.LOAD_NO_CACHE

            // Файлы: только внутри распакованного пакета.
            allowFileAccess = ViewerWebPolicy.ALLOW_FILE_ACCESS
            allowContentAccess = ViewerWebPolicy.ALLOW_CONTENT_ACCESS
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = ViewerWebPolicy.ALLOW_FILE_ACCESS_FROM_FILE_URLS
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs =
                ViewerWebPolicy.ALLOW_UNIVERSAL_ACCESS_FROM_FILE_URLS

            javaScriptCanOpenWindowsAutomatically =
                ViewerWebPolicy.JAVA_SCRIPT_CAN_OPEN_WINDOWS_AUTOMATICALLY
            setSupportMultipleWindows(ViewerWebPolicy.SUPPORT_MULTIPLE_WINDOWS)
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture =
                ViewerWebPolicy.MEDIA_PLAYBACK_REQUIRES_USER_GESTURE
            builtInZoomControls = true
            displayZoomControls = false
        }

        view.isVerticalScrollBarEnabled = true
        view.webViewClient = PackageWebViewClient(root)
    }

    private inner class PackageWebViewClient(private val root: File) : WebViewClient() {
        private val resources = PackageResourceResolver(root)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val scheme = url.scheme?.lowercase()

            if (scheme == "file") {
                // Разрешаем переходы только внутри пакета.
                return !isInsidePackage(url)
            }
            if (request.hasGesture() && (scheme == "http" || scheme == "https")) {
                openInBrowser(url)
            }
            return true
        }

        override fun shouldInterceptRequest(
            view: WebView,
            request: WebResourceRequest,
        ): WebResourceResponse? {
            val resource = resources.resolve(request.url.toString()) ?: return BLOCKED
            return try {
                WebResourceResponse(
                    resource.mimeType,
                    resource.encoding,
                    resource.file.inputStream(),
                )
            } catch (_: Exception) {
                BLOCKED
            }
        }

        private fun isInsidePackage(uri: Uri): Boolean {
            val path = uri.path ?: return false
            val candidate = try {
                File(path).canonicalFile
            } catch (e: Exception) {
                return false
            }
            val base = root.path
            return candidate.path == base || candidate.path.startsWith(base + File.separator)
        }
    }

    private fun openInBrowser(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView?.saveState(outState)
    }

    @Deprecated("Простая навигация по истории WebView без AndroidX")
    override fun onBackPressed() {
        val view = webView
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        webView?.let { view ->
            // Отцепляем от иерархии до destroy(): иначе WebView остаётся во
            // layout уже мёртвым и может уронить отрисовку/утечь Activity.
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_ROOT = "ai.eva.kbviewer.extra.ROOT"
        private const val EXTRA_INDEX = "ai.eva.kbviewer.extra.INDEX"
        private const val EXTRA_TITLE = "ai.eva.kbviewer.extra.TITLE"

        private val BLOCKED: WebResourceResponse
            get() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

        fun intentFor(context: Context, pkg: EvaPackage): Intent =
            Intent(context, ViewerActivity::class.java).apply {
                putExtra(EXTRA_ROOT, pkg.root.canonicalPath)
                putExtra(EXTRA_INDEX, pkg.index.canonicalPath)
                putExtra(EXTRA_TITLE, pkg.manifest?.title)
            }
    }
}
