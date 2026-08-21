package ai.eva.kbviewer

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EvaPackageReaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var target: File

    @Before
    fun setUp() {
        target = temp.newFolder("extracted")
    }

    private fun zipOf(vararg entries: Pair<String, String>): File {
        val file = temp.newFile("package-${entries.hashCode()}.evakb")
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        file.writeBytes(bytes.toByteArray())
        return file
    }

    private val validManifest =
        """{"format":"eva.kb","version":1,"title":"Отчёт","generatedAt":"2026-08-21T10:00:00Z"}"""

    private fun openFailure(archive: File, limits: EvaLimits = EvaLimits()): String {
        val error = try {
            EvaPackageReader.open(archive, target, limits)
            null
        } catch (e: EvaPackageException) {
            e
        }
        assertTrue("ожидалась EvaPackageException", error != null)
        return error!!.message.orEmpty()
    }

    @Test
    fun opensValidArchive() {
        val archive = zipOf(
            "manifest.json" to validManifest,
            "index.html" to "<html>Привет</html>",
            "assets/app.js" to "var a = 1;",
            "data.json" to "{}",
        )

        val pkg = EvaPackageReader.open(archive, target)

        assertTrue(pkg.index.isFile)
        assertEquals("index.html", pkg.index.name)
        assertEquals("<html>Привет</html>", pkg.index.readText())
        assertTrue(File(pkg.root, "assets/app.js").isFile)
        assertEquals("Отчёт", pkg.manifest?.title)
        assertEquals(1, pkg.manifest?.version)
        assertEquals("2026-08-21T10:00:00Z", pkg.manifest?.generatedAt)
    }

    @Test
    fun opensArchiveWithoutManifest() {
        val archive = zipOf("index.html" to "<html></html>")

        val pkg = EvaPackageReader.open(archive, target)

        assertTrue(pkg.index.isFile)
        assertNull(pkg.manifest)
    }

    @Test
    fun rejectsArchiveWithoutIndex() {
        val archive = zipOf("manifest.json" to validManifest, "assets/app.js" to "1")

        assertTrue(openFailure(archive).contains("index.html"))
    }

    @Test
    fun rejectsIndexOutsideRoot() {
        val archive = zipOf("inner/index.html" to "<html></html>")

        assertTrue(openFailure(archive).contains("index.html"))
    }

    @Test
    fun rejectsZipSlipPath() {
        val archive = zipOf("index.html" to "<html></html>", "../evil.txt" to "плохо")

        val message = openFailure(archive)

        assertTrue(message.contains("путь", ignoreCase = true))
        assertFalse(File(target.parentFile, "evil.txt").exists())
    }

    @Test
    fun rejectsDeepZipSlipPath() {
        val archive = zipOf("index.html" to "<html></html>", "assets/../../evil.txt" to "плохо")

        assertTrue(openFailure(archive).contains("путь", ignoreCase = true))
        assertFalse(File(target.parentFile, "evil.txt").exists())
    }

    @Test
    fun rejectsAbsolutePath() {
        val archive = zipOf("index.html" to "<html></html>", "/tmp/evil.txt" to "плохо")

        assertTrue(openFailure(archive).contains("путь", ignoreCase = true))
    }

    @Test
    fun rejectsWindowsStylePath() {
        val archive = zipOf("index.html" to "<html></html>", "assets\\..\\evil.txt" to "плохо")

        assertTrue(openFailure(archive).contains("путь", ignoreCase = true))
    }

    @Test
    fun rejectsTooManyEntries() {
        val entries = mutableListOf("index.html" to "<html></html>")
        repeat(5) { entries += "file$it.txt" to "x" }

        val archive = zipOf(*entries.toTypedArray())

        val message = openFailure(archive, EvaLimits(maxEntries = 4))
        assertTrue(message.contains("файлов"))
    }

    @Test
    fun rejectsTooLargeCompressedInput() {
        val archive = zipOf("index.html" to "<html></html>")

        val message = openFailure(archive, EvaLimits(maxCompressedBytes = 8))
        assertTrue(message.contains("велик", ignoreCase = true))
    }

    @Test
    fun rejectsTooLargeUncompressedContent() {
        val archive = zipOf("index.html" to "<html></html>", "big.txt" to "x".repeat(4096))

        val message = openFailure(archive, EvaLimits(maxUncompressedBytes = 512))
        assertTrue(message.contains("размер", ignoreCase = true))
    }

    @Test
    fun rejectsUnsupportedManifestVersion() {
        val archive = zipOf(
            "manifest.json" to """{"format":"eva.kb","version":2}""",
            "index.html" to "<html></html>",
        )

        assertTrue(openFailure(archive).contains("версия", ignoreCase = true))
    }

    @Test
    fun rejectsUnknownManifestFormat() {
        val archive = zipOf(
            "manifest.json" to """{"format":"other.kb","version":1}""",
            "index.html" to "<html></html>",
        )

        assertTrue(openFailure(archive).contains("формат", ignoreCase = true))
    }

    @Test
    fun rejectsBrokenManifestJson() {
        val archive = zipOf(
            "manifest.json" to "{не json",
            "index.html" to "<html></html>",
        )

        assertTrue(openFailure(archive).contains("manifest.json"))
    }

    /**
     * Битый пакет: запись-файл `assets` и запись-файл `assets/app.js` внутри неё.
     * Распаковщик обязан выдать понятную русскую ошибку, а не пробросить
     * FileNotFoundException с приватным путём приложения наружу.
     */
    @Test
    fun rejectsFileAndDirectoryPathCollision() {
        val archive = zipOf(
            "index.html" to "<html></html>",
            "assets" to "это файл, а не каталог",
            "assets/app.js" to "var a = 1;",
        )

        val message = openFailure(archive)

        assertTrue("сообщение должно быть про пакет: $message", message.contains("пакет"))
        assertFalse(
            "приватный путь не должен попадать в сообщение: $message",
            message.contains(target.path),
        )
    }

    /** Обратный порядок: сначала каталог `assets/`, потом запись-файл `assets`. */
    @Test
    fun rejectsDirectoryAndFilePathCollision() {
        val archive = zipOf(
            "index.html" to "<html></html>",
            "assets/app.js" to "var a = 1;",
            "assets" to "это файл, а не каталог",
        )

        val message = openFailure(archive)

        assertTrue("сообщение должно быть про пакет: $message", message.contains("пакет"))
        assertFalse(
            "приватный путь не должен попадать в сообщение: $message",
            message.contains(target.path),
        )
    }

    @Test
    fun rejectsNonZipFile() {
        val file = temp.newFile("broken.evakb")
        file.writeText("это вообще не архив")

        assertTrue(openFailure(file).isNotEmpty())
    }

    @Test
    fun cleansTargetDirectoryBeforeExtraction() {
        val stale = File(target, "stale/old.txt")
        stale.parentFile?.mkdirs()
        stale.writeText("старое")
        val archive = zipOf("index.html" to "<html></html>")

        EvaPackageReader.open(archive, target)

        assertFalse(stale.exists())
        assertFalse(File(target, "stale").exists())
    }

    @Test
    fun leavesNoFilesAfterFailedExtraction() {
        val archive = zipOf("index.html" to "<html></html>", "../evil.txt" to "плохо")

        openFailure(archive)

        assertFalse(File(target, "index.html").exists())
    }

    @Test
    fun createsNestedDirectories() {
        val archive = zipOf(
            "index.html" to "<html></html>",
            "assets/deep/nested/style.css" to "body{}",
        )

        val pkg = EvaPackageReader.open(archive, target)

        assertTrue(File(pkg.root, "assets/deep/nested/style.css").isFile)
    }

    @Test
    fun stagesIncomingStreamToFile() {
        val dest = File(temp.newFolder("staging"), "input.evakb")

        val staged = EvaPackageReader.stage("данные пакета".byteInputStream(), dest)

        assertEquals("данные пакета", staged.readText())
    }

    @Test
    fun stageRejectsStreamOverCompressedLimit() {
        val dest = File(temp.newFolder("staging-big"), "input.evakb")
        val payload = "x".repeat(4096).byteInputStream()

        val error = try {
            EvaPackageReader.stage(payload, dest, EvaLimits(maxCompressedBytes = 512))
            null
        } catch (e: EvaPackageException) {
            e
        }

        assertTrue("ожидалась EvaPackageException", error != null)
        assertTrue(error!!.message.orEmpty().contains("велик", ignoreCase = true))
        assertFalse("частичный файл должен быть удалён", dest.exists())
    }

    /**
     * Смыкает шов между упаковщиком на Python и читателем на Kotlin: реальный
     * пакет из dist/ должен открываться с боевыми лимитами. Если пакет ещё не
     * собран, тест пропускается — сборка не обязана его иметь.
     */
    @Test
    fun opensRealSamplePackage() {
        val sample = File("../dist/sample-dashboard.evakb")
        assumeTrue("нет ${sample.absolutePath}, пропускаем", sample.isFile)

        val pkg = EvaPackageReader.open(sample, target)

        assertEquals("База знаний Eva — сводка", pkg.manifest?.title)
        assertEquals(1, pkg.manifest?.version)
        assertTrue(pkg.index.readText().contains("assets/app.js"))
        assertTrue(File(pkg.root, "assets/app.js").isFile)
        assertTrue(File(pkg.root, "assets/style.css").isFile)
        assertTrue(File(pkg.root, "data.json").isFile)
    }

    @Test
    fun productionLimitsMatchSpecification() {
        val limits = EvaLimits()

        assertEquals(300, limits.maxEntries)
        assertEquals(25L * 1024 * 1024, limits.maxCompressedBytes)
        assertEquals(75L * 1024 * 1024, limits.maxUncompressedBytes)
    }
}
