package ai.eva.kbviewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PackageResourceResolverTest {
    @Test
    fun resolvesImageInsidePackageWithMimeType() {
        val root = Files.createTempDirectory("evakb-root").toFile()
        val image = File(root, "assets/previews/doc-abc.jpg")
        image.parentFile.mkdirs()
        image.writeBytes(byteArrayOf(1, 2, 3))

        val resource = PackageResourceResolver(root).resolve(image.toURI().toString())

        requireNotNull(resource)
        assertEquals(image.canonicalFile, resource.file)
        assertEquals("image/jpeg", resource.mimeType)
        assertNull(resource.encoding)
        assertTrue(resource.file.inputStream().use { it.read() == 1 })
    }

    @Test
    fun rejectsFileOutsidePackage() {
        val root = Files.createTempDirectory("evakb-root").toFile()
        val outside = Files.createTempFile("secret", ".jpg").toFile()

        assertNull(PackageResourceResolver(root).resolve(outside.toURI().toString()))
    }
}
