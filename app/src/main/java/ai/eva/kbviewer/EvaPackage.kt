package ai.eva.kbviewer

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.json.JSONObject

/** Ограничения на пакет. Значения по умолчанию — «боевые», в тестах переопределяются. */
data class EvaLimits(
    val maxEntries: Int = 300,
    val maxCompressedBytes: Long = 25L * 1024 * 1024,
    val maxUncompressedBytes: Long = 75L * 1024 * 1024,
)

/** Ошибка пакета: сообщение уже готово для показа пользователю. */
class EvaPackageException(message: String) : Exception(message)

data class EvaManifest(
    val format: String?,
    val version: Int,
    val title: String?,
    val generatedAt: String?,
)

/** Успешно распакованный пакет. */
data class EvaPackage(
    val root: File,
    val index: File,
    val manifest: EvaManifest?,
)

/**
 * Проверка и распаковка пакета .evakb (обычный ZIP) в приватный каталог приложения.
 * Любая проблема — [EvaPackageException] с коротким сообщением на русском.
 */
object EvaPackageReader {

    const val FORMAT_ID = "eva.kb"
    const val FORMAT_VERSION = 1

    private const val INDEX_NAME = "index.html"
    private const val MANIFEST_NAME = "manifest.json"
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Копирует входной поток (обычно content:// от VK или файлового менеджера) в [dest],
     * обрывая копирование, как только превышен лимит на размер архива.
     */
    fun stage(input: InputStream, dest: File, limits: EvaLimits = EvaLimits()): File {
        dest.parentFile?.mkdirs()
        try {
            input.use { source ->
                dest.outputStream().use { output ->
                    copyBounded(source, output, limits.maxCompressedBytes)
                }
            }
        } catch (e: EvaPackageException) {
            dest.delete()
            throw EvaPackageException(
                "Файл слишком велик: максимум ${mib(limits.maxCompressedBytes)} МиБ."
            )
        } catch (e: Exception) {
            dest.delete()
            throw EvaPackageException("Не удалось прочитать выбранный файл.")
        }
        return dest
    }

    fun open(archive: File, targetDir: File, limits: EvaLimits = EvaLimits()): EvaPackage {
        if (!archive.isFile) {
            throw EvaPackageException("Файл пакета не найден.")
        }
        if (archive.length() > limits.maxCompressedBytes) {
            throw EvaPackageException(
                "Файл слишком велик: максимум ${mib(limits.maxCompressedBytes)} МиБ."
            )
        }

        wipe(targetDir)
        if (!targetDir.mkdirs() && !targetDir.isDirectory) {
            throw EvaPackageException("Нет доступа к каталогу распаковки.")
        }

        try {
            extract(archive, targetDir, limits)

            val index = File(targetDir, INDEX_NAME)
            if (!index.isFile) {
                throw EvaPackageException("В пакете нет обязательного файла $INDEX_NAME в корне.")
            }
            return EvaPackage(targetDir, index, readManifest(File(targetDir, MANIFEST_NAME)))
        } catch (e: Throwable) {
            wipe(targetDir)
            throw e
        }
    }

    private fun extract(archive: File, targetDir: File, limits: EvaLimits) {
        val zip = try {
            ZipFile(archive)
        } catch (e: Exception) {
            throw EvaPackageException("Файл не является корректным ZIP-архивом .evakb.")
        }

        zip.use {
            var count = 0
            var written = 0L
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                count++
                if (count > limits.maxEntries) {
                    throw EvaPackageException(
                        "Слишком много файлов в пакете: максимум ${limits.maxEntries}."
                    )
                }

                val target = resolveSafely(targetDir, entry.name)
                if (entry.isDirectory) {
                    if (!target.isDirectory && !target.mkdirs()) {
                        throw damaged(entry.name)
                    }
                    continue
                }

                written += writeEntry(zip, entry, target, limits.maxUncompressedBytes - written)
            }
        }
    }

    /**
     * Пишет одну запись архива на диск. Битый пакет (например, запись-файл `assets`
     * и запись `assets/app.js` внутри неё) не должен ронять приложение или показывать
     * пользователю приватный путь из системного исключения.
     */
    private fun writeEntry(zip: ZipFile, entry: ZipEntry, target: File, remaining: Long): Long {
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw damaged(entry.name)
        }
        if (target.isDirectory) {
            throw damaged(entry.name)
        }

        return try {
            zip.getInputStream(entry).use { input ->
                target.outputStream().use { output -> copyBounded(input, output, remaining) }
            }
        } catch (e: EvaPackageException) {
            throw e // лимит размера — своё понятное сообщение уже есть
        } catch (e: IOException) {
            throw damaged(entry.name)
        }
    }

    private fun damaged(entryName: String) =
        EvaPackageException("Структура пакета повреждена: не удалось распаковать «$entryName».")

    /**
     * Превращает имя записи в путь внутри [root], отсекая zip-slip и абсолютные пути.
     */
    private fun resolveSafely(root: File, rawName: String): File {
        val name = rawName.trimEnd('/')
        val invalid = name.isBlank() ||
            name.contains('\\') ||
            name.startsWith('/') ||
            name.contains('\u0000') ||
            (name.length >= 2 && name[1] == ':') ||
            name.split('/').any { it == ".." || it == "." }
        if (invalid) {
            throw EvaPackageException("Недопустимый путь внутри пакета: $rawName")
        }

        val resolved = File(root, name).canonicalFile
        val prefix = root.canonicalPath + File.separator
        if (!resolved.path.startsWith(prefix)) {
            throw EvaPackageException("Недопустимый путь внутри пакета: $rawName")
        }
        return resolved
    }

    private fun copyBounded(input: InputStream, output: OutputStream, remaining: Long): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            copied += read
            if (copied > remaining) {
                throw EvaPackageException("Суммарный размер содержимого пакета слишком большой.")
            }
            output.write(buffer, 0, read)
        }
        return copied
    }

    private fun readManifest(file: File): EvaManifest? {
        if (!file.isFile) return null

        val json = try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            throw EvaPackageException("Файл $MANIFEST_NAME повреждён или это не JSON-объект.")
        }

        val format = json.optString("format").takeIf { it.isNotEmpty() }
        if (format != null && format != FORMAT_ID) {
            throw EvaPackageException("Неизвестный формат пакета: $format")
        }

        val version = json.optInt("version", -1)
        if (version != FORMAT_VERSION) {
            throw EvaPackageException(
                "Неподдерживаемая версия формата: $version (поддерживается $FORMAT_VERSION)."
            )
        }

        return EvaManifest(
            format = format,
            version = version,
            title = json.optString("title").takeIf { it.isNotEmpty() },
            generatedAt = json.optString("generatedAt").takeIf { it.isNotEmpty() },
        )
    }

    private fun wipe(dir: File) {
        if (dir.exists()) {
            dir.deleteRecursively()
        }
    }

    private fun mib(bytes: Long): Long = bytes / (1024 * 1024)
}
