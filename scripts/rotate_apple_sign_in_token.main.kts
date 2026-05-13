#!/usr/bin/env kotlin

import java.io.File
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

fun prompt(label: String, default: String? = null, allowEmpty: Boolean = false): String {
    val suffix = default?.let { " [$it]" } ?: ""
    while (true) {
        print("$label$suffix: ")
        val input = readlnOrNull()?.trim().orEmpty()
        if (input.isEmpty()) {
            if (default != null) return default
            if (allowEmpty) return ""
        } else {
            return input
        }
    }
}

fun loadPrivateKey(path: String): PrivateKey {
    val pem = File(path)
        .readLines()
        .filterNot { it.startsWith("---") }
        .joinToString(separator = "")

    val decoded = Base64.getDecoder().decode(pem)
    val keySpec = PKCS8EncodedKeySpec(decoded)
    return KeyFactory.getInstance("EC").generatePrivate(keySpec)
}

fun base64Url(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

fun derSignatureToJose(der: ByteArray): ByteArray {
    require(der.firstOrNull() == 0x30.toByte()) { "Invalid DER signature" }
    var offset = 2 // skip sequence tag + length byte (only supports shorter lengths which is fine here)
    fun readLength(): Int {
        var length = der[offset++].toInt() and 0xFF
        if (length and 0x80 != 0) {
            val byteCount = length and 0x7F
            length = 0
            repeat(byteCount) {
                length = (length shl 8) or (der[offset++].toInt() and 0xFF)
            }
        }
        return length
    }

    fun readInteger(): ByteArray {
        require(der[offset++] == 0x02.toByte()) { "Expected integer" }
        val len = readLength()
        val value = der.copyOfRange(offset, offset + len)
        offset += len
        return value
    }

    val r = readInteger()
    val s = readInteger()

    fun toFixed(bytes: ByteArray): ByteArray {
        val result = ByteArray(32)
        val src = bytes.dropWhile { it == 0.toByte() }.toByteArray()
        require(src.size <= 32) { "Integer too large" }
        System.arraycopy(src, 0, result, 32 - src.size, src.size)
        return result
    }

    return toFixed(r) + toFixed(s)
}

val teamId = prompt("Apple Team ID")
val keyId = prompt("Apple Key ID")
val clientId = prompt("Apple Services ID (client_id)")
val p8Path = prompt("Path to AuthKey_<KEYID>.p8")
val validityDaysInput = prompt("Validity in days (max 180)", default = "180")
val validityDays = validityDaysInput.toLongOrNull()?.coerceIn(1, 180) ?: 180

val issuedAt = Instant.now().epochSecond
val expiresAt = issuedAt + validityDays * 24 * 60 * 60
val expirationDate = Instant.ofEpochSecond(expiresAt)
    .atZone(ZoneOffset.UTC)
    .format(DateTimeFormatter.ISO_INSTANT)

val headerJson = """{"alg":"ES256","kid":"$keyId"}"""
val payloadJson = """{"iss":"$teamId","iat":$issuedAt,"exp":$expiresAt,"aud":"https://appleid.apple.com","sub":"$clientId"}"""

val signingInput = base64Url(headerJson.toByteArray()) + "." + base64Url(payloadJson.toByteArray())
val privateKey = loadPrivateKey(p8Path)

val signature = Signature.getInstance("SHA256withECDSA").apply {
    initSign(privateKey)
    update(signingInput.toByteArray())
}

val joseSignature = derSignatureToJose(signature.sign())
val jwt = "$signingInput.${base64Url(joseSignature)}"

println("\n================ Apple Client Secret ==================")
println(jwt)
println("======================================================\n")
println("Expires (UTC): $expirationDate")
println("Validity days: $validityDays")
println()
println("Next steps:")
println("1. Open Supabase provider settings: https://supabase.com/dashboard/project/mfozvowjsxdwrslyoyrf/auth/providers?provider=Apple")
println("2. Paste this token into the 'Secret Key' field along with your Client ID ($clientId), Team ID ($teamId), and Key ID ($keyId).")
println("3. Save the provider configuration.")
println("4. Set a reminder before $expirationDate to rerun this script and rotate the token.")
