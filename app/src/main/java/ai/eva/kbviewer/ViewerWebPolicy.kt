package ai.eva.kbviewer

/**
 * Решения по настройкам WebView для просмотра пакета.
 *
 * Вынесены из [ViewerActivity] отдельным объектом потому, что android.webkit
 * в JVM-тестах — заглушка: сам вызов настроек не проверить, а вот сами решения
 * проверять обязательно, они уже один раз ломали приложение.
 */
internal object ViewerWebPolicy {

    /** Графики в пакете рисуются на JS. */
    const val JAVA_SCRIPT_ENABLED = true

    const val DOM_STORAGE_ENABLED = true

    /** Пакет обязан быть автономным: http/https не грузим. */
    const val BLOCK_NETWORK_LOADS = true

    /**
     * Включать нельзя.
     *
     * WebSettings.setBlockNetworkImage(true) по названию блокирует только
     * «сетевые» картинки, но в Android WebView он выключает загрузку картинок
     * целиком, независимо от схемы, — вместе с file:// из самого пакета.
     * Из-за этого превью документов показывались битой иконкой.
     *
     * Для автономности флаг и не нужен: сети у приложения нет вообще
     * (в манифесте не запрошен INTERNET), плюс [BLOCK_NETWORK_LOADS] и
     * перехват запросов, который отдаёт пустой ответ на всё, что лежит
     * вне распакованного пакета.
     */
    const val BLOCK_NETWORK_IMAGE = false

    /** Файлы: только внутри распакованного пакета. */
    const val ALLOW_FILE_ACCESS = true
    const val ALLOW_CONTENT_ACCESS = false
    const val ALLOW_FILE_ACCESS_FROM_FILE_URLS = false
    const val ALLOW_UNIVERSAL_ACCESS_FROM_FILE_URLS = false

    const val JAVA_SCRIPT_CAN_OPEN_WINDOWS_AUTOMATICALLY = false
    const val SUPPORT_MULTIPLE_WINDOWS = false
    const val MEDIA_PLAYBACK_REQUIRES_USER_GESTURE = true
}
