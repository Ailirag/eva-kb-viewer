package ai.eva.kbviewer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.io.File

/**
 * Стартовый экран: одна кнопка выбора пакета + обработка входящего ACTION_VIEW.
 * Тяжёлая работа (копирование и распаковка) уходит в фоновый поток.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var openButton: Button
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        openButton = findViewById(R.id.open_button)
        openButton.setOnClickListener { pickPackage() }

        if (savedInstanceState == null) {
            handleViewIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleViewIntent(intent)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        load(uri)
    }

    private fun pickPackage() {
        val picker = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    EVA_MIME,
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                ),
            )
        }
        try {
            startActivityForResult(picker, REQUEST_OPEN_DOCUMENT)
        } catch (e: ActivityNotFoundException) {
            showError(getString(R.string.error_no_picker))
        }
    }

    @Deprecated("Достаточно для одного запроса без AndroidX Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_OPEN_DOCUMENT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        load(uri)
    }

    private fun load(uri: Uri) {
        if (busy) return
        busy = true
        openButton.isEnabled = false
        hideError()

        Thread {
            val result = runCatching { readPackage(uri) }
            runOnUiThread {
                busy = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                openButton.isEnabled = true
                result.fold(
                    onSuccess = { showPackage(it) },
                    onFailure = { showError(describe(it)) },
                )
            }
        }.start()
    }

    private fun readPackage(uri: Uri): EvaPackage {
        val input = try {
            contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        } ?: throw EvaPackageException(getString(R.string.error_read_uri))

        val staged = EvaPackageReader.stage(input, File(cacheDir, "incoming/package.evakb"))
        try {
            return EvaPackageReader.open(staged, File(cacheDir, "package"))
        } finally {
            staged.delete()
        }
    }

    private fun showPackage(pkg: EvaPackage) {
        startActivity(ViewerActivity.intentFor(this, pkg))
    }

    /**
     * Пользователю показываем только свои сообщения: тексты системных исключений
     * бывают на английском и содержат приватные пути приложения.
     */
    private fun describe(error: Throwable): String = when (error) {
        is EvaPackageException -> error.message?.takeIf { it.isNotBlank() }
            ?: getString(R.string.error_read_uri)
        else -> getString(R.string.error_read_uri)
    }

    private fun showError(message: String) {
        status.text = getString(R.string.error_title) + ":\n" + message
        status.visibility = View.VISIBLE
    }

    private fun hideError() {
        status.visibility = View.GONE
    }

    private companion object {
        const val REQUEST_OPEN_DOCUMENT = 101
        const val EVA_MIME = "application/vnd.eva.kb+zip"
    }
}
