package vn.thanhtaxixanh.camera.ai

import android.content.Context
import java.net.URI

/** Camera profiles observed in xADAS 0.2.7. Only validated adapters are enabled. */
object CameraSourceCatalog {
    const val PREFS = "ttx_camera_source"
    const val PROFILE_KEY = "profile"
    const val URL_KEY = "custom_rtsp"

    enum class Profile(val label: String, val supported: Boolean) {
        A500S("70mai A500S / DR2500", true),
        CUSTOM("RTSP tùy chỉnh", true),
        HISI("70mai HiSilicon – chưa hỗ trợ", false),
        MSTAR("70mai MStar – chưa hỗ trợ", false),
        VIETMAP("VIETMAP – chưa hỗ trợ", false),
        VIETMAP_KC01("VIETMAP KC01 – chưa hỗ trợ", false),
        VIETMAP_M1("VIETMAP M1 – chưa hỗ trợ", false),
        VIETMAP_NOVATEK("VIETMAP Novatek – chưa hỗ trợ", false),
        VIETMAP_V740("VIETMAP V740 – chưa hỗ trợ", false),
        AZCAM("AZCAM – chưa hỗ trợ", false),
        USB_UVC("USB / UVC – chưa hỗ trợ", false),
        PHONE("Camera điện thoại / YUV420", true)
    }

    fun selected(context: Context): Profile {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PROFILE_KEY, Profile.A500S.name)
        return Profile.values().firstOrNull { it.name == saved } ?: Profile.A500S
    }

    fun validateUrl(value: String): String {
        val text = value.trim()
        require(text.length in 10..512 && !text.contains('\r') && !text.contains('\n'))
        val uri = URI(text)
        require(uri.scheme.equals("rtsp", ignoreCase = true) && !uri.host.isNullOrBlank())
        require(uri.userInfo == null) { "Chưa hỗ trợ nhập thông tin xác thực vào URL" }
        return text
    }

    fun save(context: Context, profile: Profile, customUrl: String) {
        require(profile.supported) { "Profile camera chưa có adapter hoạt động" }
        if (profile == Profile.CUSTOM) validateUrl(customUrl)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PROFILE_KEY, profile.name)
            .putString(URL_KEY, customUrl.trim()).apply()
    }

    fun resolve(context: Context, gateway: String): String = when(val p = selected(context)) {
        Profile.A500S -> {
            require(Regex("\\d{1,3}(\\.\\d{1,3}){3}").matches(gateway))
            "rtsp://" + gateway + ":554/livestream/12"
        }
        Profile.CUSTOM -> validateUrl(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(URL_KEY, "").orEmpty())
        else -> throw IllegalStateException(p.label + ": chưa có adapter")
    }
}
