package com.example.data.network

import android.util.Base64
import com.example.data.model.DesktopNodeConfig
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date

object PairingConfigParser {
    /** A server certificate alone is shared by multiple separately authorized device pairings. */
    fun destinationIdentity(config: DesktopNodeConfig): String {
        if (config.simulatedNodeEnabled) return "DEMO"
        if (!config.isPaired) return ""
        return MessageDigest.getInstance("SHA-256").digest(
            ("private-ai-device-v1|" + config.certificateFingerprint + "|" + config.pairKey).toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun parse(text: String, previous: DesktopNodeConfig = DesktopNodeConfig()): DesktopNodeConfig {
        require(text.length <= 16000) { "Pairing link is too large." }
        val uri = URI(text.trim())
        require(uri.scheme == "privateai" && uri.host == "pair" && uri.rawPath.orEmpty().isEmpty() && uri.fragment == null && uri.userInfo == null) { "Paste the complete privateai://pair link from the desktop." }
        val values = linkedMapOf<String, String>()
        uri.rawQuery.orEmpty().split("&").forEach {
            val parts = it.split("=", limit = 2)
            require(parts.size == 2) { "Incomplete pairing link." }
            val key = URLDecoder.decode(parts[0], "UTF-8")
            require(!values.containsKey(key)) { "Duplicate pairing field." }
            values[key] = URLDecoder.decode(parts[1], "UTF-8")
        }
        val required = setOf("host", "port", "scheme", "key", "fingerprint", "certificate")
        require(values.keys.containsAll(required) && values.keys.all { it in required || it == "notificationKey" }) { "Incomplete or unsupported pairing link." }
        require(values["notificationKey"] == null || Regex("[A-Za-z0-9_-]{43}").matches(values.getValue("notificationKey"))) { "Invalid restricted notification key." }
        val config = previous.copy(
            hostIp = values.getValue("host"), port = values.getValue("port").toInt(),
            scheme = values.getValue("scheme"), pairKey = values.getValue("key"),
            certificateDer = values.getValue("certificate"), certificateFingerprint = values.getValue("fingerprint"),
            simulatedNodeEnabled = false, notificationKey = values["notificationKey"].orEmpty(), backgroundNotificationsEnabled = false
        )
        certificate(config)
        return config
    }

    fun certificate(config: DesktopNodeConfig, now: Date = Date()): X509Certificate {
        require(config.scheme == "https" && config.port in 1..65535 && privateAddress(config.hostIp)) { "Pair only the selected private IPv4 desktop address over HTTPS." }
        require(Regex("[A-Za-z0-9_-]{43}").matches(config.pairKey)) { "Use the per-device key from a new desktop pairing link." }
        require(Regex("[a-f0-9]{64}").matches(config.certificateFingerprint)) { "Missing certificate fingerprint." }
        require(config.certificateDer.length in 100..12000 && Regex("[A-Za-z0-9+/]*={0,2}").matches(config.certificateDer)) { "Invalid paired certificate." }
        val bytes = Base64.decode(config.certificateDer, Base64.NO_WRAP)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        require(cert.encoded.contentEquals(bytes)) { "Unexpected certificate data." }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        require(MessageDigest.isEqual(hash.toByteArray(), config.certificateFingerprint.toByteArray())) { "Paired certificate fingerprint does not match." }
        cert.checkValidity(now)
        require(cert.basicConstraints == -1 && cert.extendedKeyUsage?.contains("1.3.6.1.5.5.7.3.1") == true) { "Certificate is not a desktop server certificate." }
        require(cert.subjectAlternativeNames.orEmpty().any { it[0] == 7 && it[1] == config.hostIp }) { "Paired certificate does not match the desktop address. Pair again after changing networks." }
        return cert
    }

    fun privateAddress(host: String): Boolean {
        val parts = host.split(".")
        if (parts.size != 4 || parts.any { !Regex("0|[1-9][0-9]{0,2}").matches(it) || it.toInt() > 255 }) return false
        val n = parts.map(String::toInt)
        return n[0] == 10 || n[0] == 127 || (n[0] == 192 && n[1] == 168) ||
            (n[0] == 172 && n[1] in 16..31) || (n[0] == 100 && n[1] in 64..127)
    }
}
