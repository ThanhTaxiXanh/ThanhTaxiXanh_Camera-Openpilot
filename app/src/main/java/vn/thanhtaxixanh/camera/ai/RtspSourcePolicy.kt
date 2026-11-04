package vn.thanhtaxixanh.camera.ai

import java.net.URI

/**
 * Pure RTSP address boundary for owner-specified camera sources.
 * This only checks syntax and potentially dangerous local-endpoint
 * confusion; it cannot establish camera identity, availability or
 * whether a third-party device requires separate authorization.
 */
object RtspSourcePolicy {
    fun isValidIpv4(value:String):Boolean {
        val parts=value.split('.')
        if(parts.size!=4 || parts.any {
            it.isEmpty() || it.length>3 || !it.all(Char::isDigit) ||
                (it.length>1 && it.first()=='0')
        }) return false
        val nums=parts.map { it.toIntOrNull() ?: return false }
        return nums.all { it in 0..255 } &&
            nums[0] in 1..223 && nums[0]!=127 &&
            nums != listOf(169,254,0,0)
    }

    fun check(url:String):String {
        val trimmed=url.trim()
        require(trimmed.length in 10..512 &&
            trimmed.none { it=='\r' || it=='\n' || it=='\u0000' }) {
            "Invalid RTSP URL length or control characters"
        }
        val uri=URI(trimmed)
        require(uri.scheme.equals("rtsp",ignoreCase=true)) {
            "Only rtsp:// URLs are allowed"
        }
        val host=uri.host
        require(!host.isNullOrBlank() && uri.rawUserInfo==null &&
            uri.rawFragment==null) { "Missing camera host, credentials or fragment" }
        require(uri.port == -1 || uri.port in 1..65535) {
            "Invalid RTSP TCP port"
        }
        require(!host.equals("localhost",true) && host!="0.0.0.0" &&
            host!="::1" && host!="[::1]") { "Loopback/any-address is not a camera" }
        if(host.all { it.isDigit() || it=='.' }) {
            require(isValidIpv4(host)) { "Invalid camera IPv4 address" }
        }
        return trimmed
    }

    fun gateway(gateway:String):String {
        require(isValidIpv4(gateway)) { "Camera Wi-Fi gateway is invalid" }
        return gateway
    }
}
