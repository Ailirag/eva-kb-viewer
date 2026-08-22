package ai.eva.kbviewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerWebPolicyTest {

    /**
     * Регрессия: превью документов в пакете показывались битой иконкой.
     *
     * Причина — setBlockNetworkImage(true): в Android WebView он выключает
     * картинки целиком, а не только сетевые, поэтому не грузились и file://
     * из самого пакета. Флаг должен оставаться выключенным.
     */
    @Test
    fun showsImagesPackagedInsideArchive() {
        assertFalse(ViewerWebPolicy.BLOCK_NETWORK_IMAGE)
    }

    /** Автономность при этом не должна пострадать. */
    @Test
    fun keepsNetworkBlocked() {
        assertTrue(ViewerWebPolicy.BLOCK_NETWORK_LOADS)
    }

    /** И песочница для файлов тоже: читаем пакет, но не отдаём его скриптам. */
    @Test
    fun keepsFileSandbox() {
        assertTrue(ViewerWebPolicy.ALLOW_FILE_ACCESS)
        assertFalse(ViewerWebPolicy.ALLOW_CONTENT_ACCESS)
        assertFalse(ViewerWebPolicy.ALLOW_FILE_ACCESS_FROM_FILE_URLS)
        assertFalse(ViewerWebPolicy.ALLOW_UNIVERSAL_ACCESS_FROM_FILE_URLS)
    }
}
