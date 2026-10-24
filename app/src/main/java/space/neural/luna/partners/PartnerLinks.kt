package space.neural.luna.partners

import java.net.URI

/** Only construct known, fixed contact schemes; reject arbitrary externally supplied URLs. */
object PartnerLinks {
    data class OfficialApp(val packageName: String, val playUrl: String)

    /** Apps listed by the named charging networks; unknown networks receive no guessed link. */
    fun officialChargingApp(id: String): OfficialApp? = when {
        id.startsWith("energiza-") -> OfficialApp("br.com.energiza.app", "https://play.google.com/store/apps/details?id=br.com.energiza.app")
        id.startsWith("posto-rede-hg-") || id.contains("neocharge", true) -> OfficialApp("br.com.voltbras.neocharge", "https://play.google.com/store/apps/details?id=br.com.voltbras.neocharge")
        id.startsWith("plug31-") || id.contains("plug31", true) -> OfficialApp("com.tupi.mucura", "https://play.google.com/store/apps/details?id=com.tupi.mucura")
        id.startsWith("va-e-mob-") || id.contains("va-e-mob", true) -> OfficialApp("com.vaemob.app", "https://play.google.com/store/apps/details?id=com.vaemob.app")
        else -> null
    }

    fun whatsapp(value: String?): String? = phoneDigits(value)?.takeIf { it.length in 10..15 }?.let { "https://wa.me/$it" }
    fun phone(value: String?): String? = phoneDigits(value)?.let { "tel:${if (value?.trim()?.startsWith('+') == true) "+" else ""}$it" }
    fun instagram(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val name = if (value.startsWith("https://")) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (uri.host !in setOf("instagram.com", "www.instagram.com") || uri.userInfo != null || uri.query != null || uri.fragment != null || uri.port != -1) return null
            uri.path.orEmpty().trim('/')
        } else value.removePrefix("@").trim()
        return name.takeIf { it.matches(Regex("[A-Za-z0-9_][A-Za-z0-9_.]{0,29}")) && !it.contains("..") }?.let { "https://www.instagram.com/$it/" }
    }
    fun safeSource(value: String): String? = runCatching {
        val uri = URI(value)
        value.takeIf { uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.port in setOf(-1,443) }
    }.getOrNull()
    fun safeImageAsset(value: String?): String? = value?.takeIf {
        !it.startsWith('/') && !it.contains("..") && it.matches(Regex("[A-Za-z0-9_/-]+\\.(png|jpg|jpeg|webp)", RegexOption.IGNORE_CASE))
    }
    private fun phoneDigits(value: String?): String? {
        if (value.isNullOrBlank() || !value.trim().matches(Regex("\\+?[0-9 ()-]+"))) return null
        return value.filter { it.isDigit() }.takeIf { it.length in 8..15 }
    }
}
