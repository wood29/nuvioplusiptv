package com.nuvio.app.features.plugins

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.refTo
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import com.nuvio.app.features.plugins.cryptointerop.*
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

internal fun pluginGetRandomValues(length: Int): ByteArray {
    require(length >= 0) { "Random byte length must be non-negative" }
    if (length == 0) return ByteArray(0)
    val bytes = ByteArray(length)
    @OptIn(ExperimentalForeignApi::class)
    val status = SecRandomCopyBytes(kSecRandomDefault, length.toULong(), bytes.refTo(0))
    require(status == 0) { "Failed to generate secure random bytes: status $status" }
    return bytes
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginDigest(algorithm: String, data: ByteArray): ByteArray {
    val normalized = normalizeDigestAlgorithm(algorithm)
    val output = ByteArray(
        when (normalized) {
            "MD5" -> CC_MD5_DIGEST_LENGTH.toInt()
            "SHA1" -> CC_SHA1_DIGEST_LENGTH.toInt()
            "SHA256" -> CC_SHA256_DIGEST_LENGTH.toInt()
            "SHA384" -> CC_SHA384_DIGEST_LENGTH.toInt()
            "SHA512" -> CC_SHA512_DIGEST_LENGTH.toInt()
            else -> error("Unsupported digest algorithm: $algorithm")
        },
    )

    data.usePinned { pinnedData ->
        output.usePinned { pinnedOutput ->
            val dataPtr = if (data.isNotEmpty()) pinnedData.addressOf(0) else null
            val outputPtr = pinnedOutput.addressOf(0).reinterpret<UByteVar>()

            when (normalized) {
                "MD5" -> CC_MD5(dataPtr, data.size.toUInt(), outputPtr)
                "SHA1" -> CC_SHA1(dataPtr, data.size.toUInt(), outputPtr)
                "SHA256" -> CC_SHA256(dataPtr, data.size.toUInt(), outputPtr)
                "SHA384" -> CC_SHA384(dataPtr, data.size.toUInt(), outputPtr)
                "SHA512" -> CC_SHA512(dataPtr, data.size.toUInt(), outputPtr)
            }
        }
    }

    return output
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginPbkdf2(
    password: ByteArray,
    salt: ByteArray,
    iterations: Int,
    keySizeBits: Int,
    algorithm: String,
): ByteArray {
    require(iterations > 0) { "PBKDF2 iterations must be positive" }
    require(keySizeBits > 0 && keySizeBits % 8 == 0) { "PBKDF2 key size must be a positive byte-aligned bit length" }

    val prf = normalizePbkdf2Prf(algorithm)
    
    val derivedKeyLen = keySizeBits / 8
    val derivedKey = ByteArray(derivedKeyLen)
    
    password.usePinned { pinnedPassword ->
        salt.usePinned { pinnedSalt ->
            derivedKey.usePinned { pinnedDerivedKey ->
                val passwordPtr = if (password.isNotEmpty()) pinnedPassword.addressOf(0).reinterpret<ByteVar>() else null
                val saltPtr = if (salt.isNotEmpty()) pinnedSalt.addressOf(0).reinterpret<UByteVar>() else null
                val derivedKeyPtr = pinnedDerivedKey.addressOf(0).reinterpret<UByteVar>()

                val status = CCKeyDerivationPBKDF(
                    algorithm = kCCPBKDF2,
                    password = passwordPtr,
                    passwordLen = password.size.toULong(),
                    salt = saltPtr,
                    saltLen = salt.size.toULong(),
                    prf = prf,
                    rounds = iterations.toUInt(),
                    derivedKey = derivedKeyPtr,
                    derivedKeyLen = derivedKeyLen.toULong()
                )
                
                require(status == kCCSuccess) { "PBKDF2 failed with status: $status" }
            }
        }
    }
    
    return derivedKey
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginAesEncrypt(
    mode: String,
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray {
    requireValidAesKey(key)
    if (!mode.uppercase().contains("ECB")) {
        require(iv.isNotEmpty()) { "AES mode $mode requires an IV" }
    }

    val isGcm = mode.uppercase().contains("GCM")
    if (isGcm) {
        return gcmEncrypt(key, iv, data)
    }
    
    val isEcb = mode.uppercase().contains("ECB")
    val isNoPadding = mode.uppercase().contains("NOPADDING")

    val dataOutAvailable = data.size + 16 // AES block size
    val dataOut = ByteArray(dataOutAvailable)
    
    var finalData: ByteArray? = null
    
    memScoped {
        val dataOutMoved = alloc<platform.posix.size_tVar>()
        
        var options = 0U
        if (isEcb) {
            options = options or kCCOptionECBMode
        }
        if (!isNoPadding) {
            options = options or kCCOptionPKCS7Padding
        }

        key.usePinned { pinnedKey ->
            iv.usePinned { pinnedIv ->
                data.usePinned { pinnedData ->
                    dataOut.usePinned { pinnedDataOut ->
                        val status = CCCrypt(
                            op = kCCEncrypt,
                            alg = kCCAlgorithmAES,
                            options = options,
                            key = if (key.isNotEmpty()) pinnedKey.addressOf(0) else null,
                            keyLength = key.size.toULong(),
                            iv = if (!isEcb && iv.isNotEmpty()) pinnedIv.addressOf(0) else null,
                            dataIn = if (data.isNotEmpty()) pinnedData.addressOf(0) else null,
                            dataInLength = data.size.toULong(),
                            dataOut = pinnedDataOut.addressOf(0),
                            dataOutAvailable = dataOutAvailable.toULong(),
                            dataOutMoved = dataOutMoved.ptr
                        )
                        
                        if (status == kCCSuccess) {
                            finalData = dataOut.copyOf(dataOutMoved.value.toInt())
                        } else {
                            error("CCCrypt Encrypt failed with status: $status")
                        }
                    }
                }
            }
        }
    }
    
    return finalData ?: ByteArray(0)
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginAesDecrypt(
    mode: String,
    key: ByteArray,
    iv: ByteArray,
    data: ByteArray,
): ByteArray {
    requireValidAesKey(key)
    if (!mode.uppercase().contains("ECB")) {
        require(iv.isNotEmpty()) { "AES mode $mode requires an IV" }
    }

    val isGcm = mode.uppercase().contains("GCM")
    if (isGcm) {
        require(data.size >= 16) { "Data too short for GCM decryption" }
        val ciphertextLen = data.size - 16
        val ciphertext = data.copyOfRange(0, ciphertextLen)
        val tagBytes = data.copyOfRange(ciphertextLen, data.size)
        
        return gcmDecrypt(key, iv, ciphertext, tagBytes)
    }
    
    val isEcb = mode.uppercase().contains("ECB")
    val isNoPadding = mode.uppercase().contains("NOPADDING")

    val dataOutAvailable = data.size + 16 // AES block size
    val dataOut = ByteArray(dataOutAvailable)
    
    var finalData: ByteArray? = null
    
    memScoped {
        val dataOutMoved = alloc<platform.posix.size_tVar>()
        
        var options = 0U
        if (isEcb) {
            options = options or kCCOptionECBMode
        }
        if (!isNoPadding) {
            options = options or kCCOptionPKCS7Padding
        }

        key.usePinned { pinnedKey ->
            iv.usePinned { pinnedIv ->
                data.usePinned { pinnedData ->
                    dataOut.usePinned { pinnedDataOut ->
                        val status = CCCrypt(
                            op = kCCDecrypt,
                            alg = kCCAlgorithmAES,
                            options = options,
                            key = if (key.isNotEmpty()) pinnedKey.addressOf(0) else null,
                            keyLength = key.size.toULong(),
                            iv = if (!isEcb && iv.isNotEmpty()) pinnedIv.addressOf(0) else null,
                            dataIn = if (data.isNotEmpty()) pinnedData.addressOf(0) else null,
                            dataInLength = data.size.toULong(),
                            dataOut = pinnedDataOut.addressOf(0),
                            dataOutAvailable = dataOutAvailable.toULong(),
                            dataOutMoved = dataOutMoved.ptr
                        )
                        
                        if (status == kCCSuccess) {
                            finalData = dataOut.copyOf(dataOutMoved.value.toInt())
                        } else {
                            error("CCCrypt failed with status: $status")
                        }
                    }
                }
            }
        }
    }
    
    return finalData ?: ByteArray(0)
}

internal fun pluginSign(algorithm: String, privateKey: ByteArray, data: ByteArray): ByteArray {
    throw UnsupportedOperationException("Asymmetric signing is currently implemented natively only on Android")
}

internal fun pluginVerify(algorithm: String, publicKey: ByteArray, signature: ByteArray, data: ByteArray): Boolean {
    throw UnsupportedOperationException("Asymmetric verification is currently implemented natively only on Android")
}

private fun UByteArray.toHex(): String = joinToString(separator = "") { byte ->
    byte.toString(16).padStart(2, '0')
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginDigestHex(algorithm: String, data: String): String {
    val normalized = normalizeDigestAlgorithm(algorithm)
    val input = data.encodeToByteArray()
    val output = UByteArray(
        when (normalized) {
            "MD5" -> CC_MD5_DIGEST_LENGTH.toInt()
            "SHA1" -> CC_SHA1_DIGEST_LENGTH.toInt()
            "SHA256" -> CC_SHA256_DIGEST_LENGTH.toInt()
            "SHA384" -> CC_SHA384_DIGEST_LENGTH.toInt()
            "SHA512" -> CC_SHA512_DIGEST_LENGTH.toInt()
            else -> error("Unsupported digest algorithm: $algorithm")
        },
    )

    input.usePinned { pinnedInput ->
        output.usePinned { pinnedOutput ->
            val dataPtr = if (input.isNotEmpty()) pinnedInput.addressOf(0) else null
            val outputPtr = pinnedOutput.addressOf(0)

            when (normalized) {
                "MD5" -> CC_MD5(dataPtr, input.size.toUInt(), outputPtr)
                "SHA1" -> CC_SHA1(dataPtr, input.size.toUInt(), outputPtr)
                "SHA256" -> CC_SHA256(dataPtr, input.size.toUInt(), outputPtr)
                "SHA384" -> CC_SHA384(dataPtr, input.size.toUInt(), outputPtr)
                "SHA512" -> CC_SHA512(dataPtr, input.size.toUInt(), outputPtr)
            }
        }
    }

    return output.toHex()
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginHmac(algorithm: String, key: ByteArray, data: ByteArray): ByteArray {
    val (alg, outputSize) = normalizeHmacAlgorithm(algorithm)
    val output = ByteArray(outputSize)

    key.usePinned { pinnedKey ->
        data.usePinned { pinnedInput ->
            output.usePinned { pinnedOutput ->
                val keyPtr = if (key.isNotEmpty()) pinnedKey.addressOf(0) else null
                val inputPtr = if (data.isNotEmpty()) pinnedInput.addressOf(0) else null

                CCHmac(
                    alg,
                    keyPtr,
                    key.size.toULong(),
                    inputPtr,
                    data.size.toULong(),
                    pinnedOutput.addressOf(0).reinterpret<UByteVar>(),
                )
            }
        }
    }

    return output
}

@OptIn(ExperimentalForeignApi::class)
internal fun pluginHmacHex(algorithm: String, key: String, data: String): String {
    return pluginHmac(algorithm, key.encodeToByteArray(), data.encodeToByteArray()).toHex()
}

private fun normalizeDigestAlgorithm(algorithm: String): String {
    return when (algorithm.normalizedAlgorithmToken()) {
        "MD5" -> "MD5"
        "SHA1" -> "SHA1"
        "SHA256" -> "SHA256"
        "SHA384" -> "SHA384"
        "SHA512" -> "SHA512"
        else -> error("Unsupported digest algorithm: $algorithm")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun normalizePbkdf2Prf(algorithm: String) =
    when (algorithm.normalizedAlgorithmToken().removePrefix("HMAC")) {
        "SHA1" -> kCCPRFHmacAlgSHA1
        "SHA256" -> kCCPRFHmacAlgSHA256
        "SHA384" -> kCCPRFHmacAlgSHA384
        "SHA512" -> kCCPRFHmacAlgSHA512
        else -> error("Unsupported PBKDF2 hash algorithm: $algorithm")
    }

@OptIn(ExperimentalForeignApi::class)
private fun normalizeHmacAlgorithm(algorithm: String) =
    when (algorithm.normalizedAlgorithmToken().removePrefix("HMAC")) {
        "MD5" -> kCCHmacAlgMD5 to CC_MD5_DIGEST_LENGTH.toInt()
        "SHA1" -> kCCHmacAlgSHA1 to CC_SHA1_DIGEST_LENGTH.toInt()
        "SHA256" -> kCCHmacAlgSHA256 to CC_SHA256_DIGEST_LENGTH.toInt()
        "SHA384" -> kCCHmacAlgSHA384 to CC_SHA384_DIGEST_LENGTH.toInt()
        "SHA512" -> kCCHmacAlgSHA512 to CC_SHA512_DIGEST_LENGTH.toInt()
        else -> error("Unsupported HMAC algorithm: $algorithm")
    }

private fun requireValidAesKey(key: ByteArray) {
    require(key.size == 16 || key.size == 24 || key.size == 32) {
        "AES key must be 16, 24, or 32 bytes"
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }

private fun String.normalizedAlgorithmToken(): String =
    uppercase()
        .replace("-", "")
        .replace("_", "")
        .replace("/", "")
        .replace(" ", "")

@OptIn(ExperimentalEncodingApi::class)
internal fun pluginBase64Encode(data: String): String =
    Base64.encode(data.encodeToByteArray())

@OptIn(ExperimentalEncodingApi::class)
internal fun pluginBase64Decode(data: String): String {
    var normalized = data.trim().replace("\n", "").replace("\r", "").replace(" ", "")
    normalized = normalized.replace("-", "+").replace("_", "/")
    val padNeeded = (4 - (normalized.length % 4)) % 4
    if (padNeeded > 0) {
        normalized += "=".repeat(padNeeded)
    }
    val decoded = Base64.decode(normalized)
    return decoded.decodeToString()
}

internal fun pluginUtf8ToHex(value: String): String =
    value.encodeToByteArray().joinToString(separator = "") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }

internal fun pluginHexToByteArray(hex: String): ByteArray {
    val normalized = hex.trim().lowercase()
        .replace(" ", "")
        .removePrefix("0x")
    if (normalized.isEmpty()) return ByteArray(0)

    val evenHex = if (normalized.length % 2 == 0) normalized else "0$normalized"
    val out = ByteArray(evenHex.length / 2)
    for (index in out.indices) {
        val part = evenHex.substring(index * 2, index * 2 + 2)
        out[index] = part.toInt(16).toByte()
    }
    return out
}

internal fun pluginHexToUtf8(hex: String): String {
    return pluginHexToByteArray(hex).decodeToString()
}


// ---------------------------------------------------------------- AES-GCM from public APIs
// Apple rejects apps that call CCCryptorGCM* (private symbols, ITMS-90338). GCM is built here
// from AES-ECB (CCCrypt, public) plus GHASH, with the same output: ciphertext followed by a
// 16-byte tag, no additional data.

@OptIn(ExperimentalForeignApi::class)
private fun aesEcbBlocks(key: ByteArray, blocks: ByteArray): ByteArray {
    if (blocks.isEmpty()) return ByteArray(0)
    val out = ByteArray(blocks.size)
    memScoped {
        val moved = alloc<platform.posix.size_tVar>()
        key.usePinned { k ->
            blocks.usePinned { b ->
                out.usePinned { o ->
                    val status = CCCrypt(
                        kCCEncrypt, kCCAlgorithmAES, kCCOptionECBMode,
                        k.addressOf(0), key.size.toULong(), null,
                        b.addressOf(0), blocks.size.toULong(),
                        o.addressOf(0), out.size.toULong(), moved.ptr
                    )
                    if (status != kCCSuccess) error("AES block encryption failed with status: $status")
                }
            }
        }
    }
    return out
}

private fun beLong(b: ByteArray, off: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xff)
    return v
}

private fun putBeLong(b: ByteArray, off: Int, v: Long) {
    for (i in 0 until 8) b[off + i] = (v ushr (56 - 8 * i)).toByte()
}

/** Multiplication in GF(2^128) as GCM defines it (bit-reflected, R = 0xE1 || 0^120). */
private fun gfMul(xh: Long, xl: Long, yh: Long, yl: Long): Pair<Long, Long> {
    var zh = 0L; var zl = 0L
    var vh = yh; var vl = yl
    for (i in 0 until 128) {
        val bit = if (i < 64) (xh ushr (63 - i)) and 1L else (xl ushr (127 - i)) and 1L
        if (bit == 1L) { zh = zh xor vh; zl = zl xor vl }
        val lsb = vl and 1L
        vl = (vl ushr 1) or (vh shl 63)
        vh = vh ushr 1
        if (lsb == 1L) vh = vh xor (0xE1L shl 56)
    }
    return zh to zl
}

private fun ghash(h: ByteArray, data: ByteArray): ByteArray {
    val hh = beLong(h, 0); val hl = beLong(h, 8)
    var yh = 0L; var yl = 0L
    var off = 0
    val block = ByteArray(16)
    while (off < data.size) {
        block.fill(0)
        data.copyInto(block, 0, off, minOf(off + 16, data.size))
        val r = gfMul(yh xor beLong(block, 0), yl xor beLong(block, 8), hh, hl)
        yh = r.first; yl = r.second
        off += 16
    }
    val out = ByteArray(16)
    putBeLong(out, 0, yh); putBeLong(out, 8, yl)
    return out
}

private fun padded16(b: ByteArray): ByteArray =
    if (b.size % 16 == 0) b else b.copyOf(b.size + (16 - b.size % 16))

private fun lengthsBlock(aBits: Long, cBits: Long): ByteArray =
    ByteArray(16).also { putBeLong(it, 0, aBits); putBeLong(it, 8, cBits) }

private fun gcmJ0(h: ByteArray, iv: ByteArray): ByteArray =
    if (iv.size == 12) iv + byteArrayOf(0, 0, 0, 1)
    else ghash(h, padded16(iv) + lengthsBlock(0, iv.size.toLong() * 8))

private fun inc32(block: ByteArray, by: Int): ByteArray {
    val out = block.copyOf()
    var c = ((out[12].toLong() and 0xff) shl 24) or ((out[13].toLong() and 0xff) shl 16) or
        ((out[14].toLong() and 0xff) shl 8) or (out[15].toLong() and 0xff)
    c = (c + by) and 0xffffffffL
    out[12] = (c ushr 24).toByte(); out[13] = (c ushr 16).toByte(); out[14] = (c ushr 8).toByte(); out[15] = c.toByte()
    return out
}

private fun gcmCtr(key: ByteArray, j0: ByteArray, input: ByteArray): ByteArray {
    if (input.isEmpty()) return ByteArray(0)
    val n = (input.size + 15) / 16
    val counters = ByteArray(n * 16)
    for (i in 0 until n) inc32(j0, i + 1).copyInto(counters, i * 16)
    val stream = aesEcbBlocks(key, counters)
    return ByteArray(input.size) { i -> (input[i].toInt() xor stream[i].toInt()).toByte() }
}

private fun gcmTag(key: ByteArray, h: ByteArray, j0: ByteArray, cipher: ByteArray): ByteArray {
    val s = ghash(h, padded16(cipher) + lengthsBlock(0, cipher.size.toLong() * 8))
    val ej0 = aesEcbBlocks(key, j0)
    return ByteArray(16) { i -> (s[i].toInt() xor ej0[i].toInt()).toByte() }
}

private fun gcmEncrypt(key: ByteArray, iv: ByteArray, plain: ByteArray): ByteArray {
    val h = aesEcbBlocks(key, ByteArray(16))
    val j0 = gcmJ0(h, iv)
    val cipher = gcmCtr(key, j0, plain)
    return cipher + gcmTag(key, h, j0, cipher)
}

private fun gcmDecrypt(key: ByteArray, iv: ByteArray, cipher: ByteArray, tag: ByteArray): ByteArray {
    val h = aesEcbBlocks(key, ByteArray(16))
    val j0 = gcmJ0(h, iv)
    val expected = gcmTag(key, h, j0, cipher)
    var diff = 0
    for (i in 0 until 16) diff = diff or (expected[i].toInt() xor tag.getOrElse(i) { 0 }.toInt())
    if (diff != 0 || tag.size != 16) error("GCM tag verification failed")
    return gcmCtr(key, j0, cipher)
}
