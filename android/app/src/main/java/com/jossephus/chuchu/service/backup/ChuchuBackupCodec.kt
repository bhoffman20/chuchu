package com.jossephus.chuchu.service.backup

import com.jossephus.chuchu.data.backup.BackupFormatException
import com.jossephus.chuchu.data.backup.BackupHostProfile
import com.jossephus.chuchu.data.backup.BackupPayload
import com.jossephus.chuchu.data.backup.BackupSshKey
import com.jossephus.chuchu.data.backup.InvalidBackupPassphraseException
import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.Transport
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object ChuchuBackupCodec {
    const val FORMAT_VERSION: Int = 1
    const val PAYLOAD_VERSION: Int = 1
    const val KDF_ID_PBKDF2_HMAC_SHA1: Int = 1
    const val CIPHER_ID_AES_256_GCM: Int = 1
    const val KDF_ITERATIONS: Int = 210_000
    const val SALT_SIZE_BYTES: Int = 16
    const val IV_SIZE_BYTES: Int = 12
    const val MAX_BACKUP_SIZE_BYTES: Int = 32 * 1024 * 1024

    private const val CONTAINER_MAGIC: Int = 0x4348424b // CHBK
    private const val PAYLOAD_MAGIC: Int = 0x4348504c // CHPL
    private const val MAX_ITEMS: Int = 100_000
    private const val MAX_STRING_BYTES: Int = 4 * 1024 * 1024
    private const val AES_KEY_SIZE_BYTES: Int = 32
    private const val GCM_TAG_SIZE_BITS: Int = 128

    private val nativeBridge = NativeBackupBridge()
    private val secureRandom = SecureRandom()

    fun encrypt(
        payload: BackupPayload,
        passphrase: CharArray,
    ): ByteArray {
        require(passphrase.isNotEmpty()) { "Backup passphrase must not be empty" }
        val plaintext = encodePayload(payload)
        return try {
            val nativeEncrypted = if (nativeBridge.isLoaded()) {
                try {
                    val result = nativeBridge.nativeEncrypt(plaintext, passphrase)
                        ?: throw BackupFormatException("Native backup encryption failed")
                    decodeNativeResult(result, allowInvalidPassphrase = false)
                } catch (_: UnsatisfiedLinkError) {
                    null
                }
            } else {
                null
            }
            nativeEncrypted ?: encryptWithJvm(plaintext, passphrase)
        } finally {
            plaintext.fill(0)
        }
    }

    @Throws(BackupFormatException::class, InvalidBackupPassphraseException::class)
    fun decrypt(bytes: ByteArray, passphrase: CharArray): BackupPayload {
        require(passphrase.isNotEmpty()) { "Backup passphrase must not be empty" }
        val plaintext = if (nativeBridge.isLoaded()) {
            try {
                val result = nativeBridge.nativeDecrypt(bytes, passphrase)
                    ?: throw BackupFormatException("Native backup decryption failed")
                decodeNativeResult(result, allowInvalidPassphrase = true)
            } catch (_: UnsatisfiedLinkError) {
                decryptWithJvm(bytes, passphrase)
            }
        } else {
            decryptWithJvm(bytes, passphrase)
        }
        return try {
            decodePayload(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    @Throws(BackupFormatException::class)
    fun encodePayload(payload: BackupPayload): ByteArray {
        validateItemCount(payload.keys.size, "key count")
        validateItemCount(payload.hosts.size, "host count")

        val writer = ByteWriter()
        fun writeStringField(value: String, label: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            if (bytes.size > MAX_STRING_BYTES) throw BackupFormatException("$label is too large")
            writer.writeInt(bytes.size)
            writer.writeBytes(bytes)
        }

        fun writeNullableStringField(value: String?, label: String) {
            writer.writeBoolean(value != null)
            if (value != null) writeStringField(value, label)
        }

        writer.writeInt(PAYLOAD_MAGIC)
        writer.writeInt(PAYLOAD_VERSION)
        writer.writeInt(payload.keys.size)
        payload.keys.forEach { key ->
            writer.writeLong(key.id)
            writeStringField(key.name, "key name")
            writeStringField(key.algorithm, "key algorithm")
            writeStringField(key.privateKeyPem, "private key")
            writeStringField(key.publicKeyOpenSsh, "public key")
            writer.writeLong(key.createdAtEpochMs)
        }

        writer.writeInt(payload.hosts.size)
        payload.hosts.forEach { host ->
            writer.writeLong(host.id)
            writeStringField(host.name, "host name")
            writeStringField(host.host, "host")
            writer.writeInt(host.port)
            writeStringField(host.username, "username")
            writeStringField(host.password, "password")
            writer.writeNullableLong(host.keyId)
            writeStringField(host.keyPassphrase, "key passphrase")
            writeStringField(host.transport.name, "transport")
            writeStringField(host.authMethod.name, "auth method")
            writer.writeBoolean(host.requireAuthOnConnect)
            writeNullableStringField(host.postConnectCommand, "post-connect command")
        }

        val encoded = writer.toByteArray()
        if (encoded.size > MAX_BACKUP_SIZE_BYTES) throw BackupFormatException("Backup payload is too large")
        return encoded
    }

    @Throws(BackupFormatException::class)
    fun decodePayload(bytes: ByteArray): BackupPayload {
        val reader = ByteReader(bytes)
        fun readStringField(label: String): String {
            val size = reader.readPositiveInt("$label length")
            if (size > MAX_STRING_BYTES) throw BackupFormatException("$label is too large")
            return String(reader.readExactBytes(size, label), Charsets.UTF_8)
        }

        fun readNullableStringField(label: String): String? =
            if (reader.readBoolean()) readStringField(label) else null

        fun <T : Enum<T>> readEnumField(label: String, values: Array<T>): T {
            val value = readStringField(label)
            return values.firstOrNull { it.name == value } ?: throw BackupFormatException("Unknown $label")
        }

        if (reader.readInt() != PAYLOAD_MAGIC) throw BackupFormatException("Invalid backup payload")
        val version = reader.readInt()
        if (version != PAYLOAD_VERSION) throw BackupFormatException("Unsupported backup payload version")

        val keyCount = reader.readPositiveInt("key count")
        validateItemCount(keyCount, "key count")
        val keys = buildList(keyCount) {
            repeat(keyCount) {
                add(
                    BackupSshKey(
                        id = reader.readLong(),
                        name = readStringField("key name"),
                        algorithm = readStringField("key algorithm"),
                        privateKeyPem = readStringField("private key"),
                        publicKeyOpenSsh = readStringField("public key"),
                        createdAtEpochMs = reader.readLong(),
                    ),
                )
            }
        }

        val hostCount = reader.readPositiveInt("host count")
        validateItemCount(hostCount, "host count")
        val hosts = buildList(hostCount) {
            repeat(hostCount) {
                add(
                    BackupHostProfile(
                        id = reader.readLong(),
                        name = readStringField("host name"),
                        host = readStringField("host"),
                        port = reader.readInt(),
                        username = readStringField("username"),
                        password = readStringField("password"),
                        keyId = reader.readNullableLong(),
                        keyPassphrase = readStringField("key passphrase"),
                        transport = readEnumField("transport", enumValues<Transport>()),
                        authMethod = readEnumField("auth method", enumValues<AuthMethod>()),
                        requireAuthOnConnect = reader.readBoolean(),
                        postConnectCommand = readNullableStringField("post-connect command"),
                    ),
                )
            }
        }

        if (!reader.isEof()) throw BackupFormatException("Unexpected trailing payload data")
        return BackupPayload(keys = keys, hosts = hosts)
    }


    @Throws(BackupFormatException::class)
    private fun encryptWithJvm(plaintext: ByteArray, passphrase: CharArray): ByteArray {
        val salt = ByteArray(SALT_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_SIZE_BYTES).also { secureRandom.nextBytes(it) }
        val metadata = encodeContainerMetadata(salt = salt, iv = iv)
        val passphraseBytes = passphraseToUtf16BigEndianBytes(passphrase)
        val keyBytes = deriveAesKey(passphraseBytes = passphraseBytes, salt = salt, iterations = KDF_ITERATIONS)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(GCM_TAG_SIZE_BITS, iv),
            )
            cipher.updateAAD(metadata)
            val ciphertext = cipher.doFinal(plaintext)
            if (ciphertext.size > MAX_BACKUP_SIZE_BYTES) {
                throw BackupFormatException("Backup ciphertext is too large")
            }
            val totalSize = metadata.size + Int.SIZE_BYTES + ciphertext.size
            if (totalSize > MAX_BACKUP_SIZE_BYTES) {
                throw BackupFormatException("Backup file is too large")
            }
            ByteWriter(initialCapacity = totalSize).apply {
                writeBytes(metadata)
                writeBytesWithLength(ciphertext)
            }.toByteArray()
        } catch (error: GeneralSecurityException) {
            throw BackupFormatException(error.message ?: "Backup encryption failed")
        } finally {
            keyBytes.fill(0)
            passphraseBytes.fill(0)
        }
    }

    @Throws(BackupFormatException::class, InvalidBackupPassphraseException::class)
    private fun decryptWithJvm(bytes: ByteArray, passphrase: CharArray): ByteArray {
        if (bytes.size > MAX_BACKUP_SIZE_BYTES) throw BackupFormatException("Backup file is too large")
        val container = readContainer(bytes)
        val primaryPassphraseBytes = passphraseToUtf16BigEndianBytes(passphrase)
        return try {
            decryptContainer(container, primaryPassphraseBytes)
        } catch (_: InvalidBackupPassphraseException) {
            val legacyPassphraseBytes = passphraseToUtf8Bytes(passphrase)
            try {
                decryptContainer(container, legacyPassphraseBytes)
            } finally {
                legacyPassphraseBytes.fill(0)
            }
        } finally {
            primaryPassphraseBytes.fill(0)
        }
    }

    private fun passphraseToUtf8Bytes(passphrase: CharArray): ByteArray {
        val byteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        return try {
            ByteArray(byteBuffer.remaining()).also { byteBuffer.get(it) }
        } finally {
            byteBuffer.clear()
            if (byteBuffer.hasArray()) {
                byteBuffer.array().fill(0)
            }
        }
    }

    @Throws(BackupFormatException::class)
    private fun readContainer(bytes: ByteArray): BackupContainer {
        val metadataWriter = ByteWriter()
        val reader = ByteReader(bytes)

        fun readMetadataInt(label: String): Int {
            val value = reader.readInt()
            metadataWriter.writeInt(value)
            return value
        }

        if (readMetadataInt("magic") != CONTAINER_MAGIC) throw BackupFormatException("Invalid backup file")
        val version = readMetadataInt("format version")
        if (version != FORMAT_VERSION) throw BackupFormatException("Unsupported backup format version")
        val kdfId = readMetadataInt("KDF")
        if (kdfId != KDF_ID_PBKDF2_HMAC_SHA1) throw BackupFormatException("Unsupported backup KDF")
        val iterations = readMetadataInt("KDF iterations")
        if (iterations != KDF_ITERATIONS) throw BackupFormatException("Unsupported backup KDF iterations")
        val cipherId = readMetadataInt("cipher")
        if (cipherId != CIPHER_ID_AES_256_GCM) throw BackupFormatException("Unsupported backup cipher")

        val saltSize = readMetadataInt("salt length")
        if (saltSize != SALT_SIZE_BYTES) throw BackupFormatException("Invalid salt")
        val salt = reader.readExactBytes(saltSize, "salt")
        metadataWriter.writeBytes(salt)

        val ivSize = readMetadataInt("initialization vector length")
        if (ivSize != IV_SIZE_BYTES) throw BackupFormatException("Invalid initialization vector")
        val iv = reader.readExactBytes(ivSize, "initialization vector")
        metadataWriter.writeBytes(iv)

        val ciphertextSize = reader.readPositiveInt("ciphertext length")
        if (ciphertextSize > MAX_BACKUP_SIZE_BYTES) {
            throw BackupFormatException("Backup ciphertext is too large")
        }
        val ciphertext = reader.readExactBytes(ciphertextSize, "ciphertext")
        if (!reader.isEof()) throw BackupFormatException("Unexpected trailing backup data")

        return BackupContainer(
            metadata = metadataWriter.toByteArray(),
            salt = salt,
            iv = iv,
            ciphertext = ciphertext,
        )
    }

    @Throws(BackupFormatException::class, InvalidBackupPassphraseException::class)
    private fun decryptContainer(container: BackupContainer, passphraseBytes: ByteArray): ByteArray {
        val keyBytes = deriveAesKey(
            passphraseBytes = passphraseBytes,
            salt = container.salt,
            iterations = KDF_ITERATIONS,
        )
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(keyBytes, "AES"),
                GCMParameterSpec(GCM_TAG_SIZE_BITS, container.iv),
            )
            cipher.updateAAD(container.metadata)
            cipher.doFinal(container.ciphertext)
        } catch (_: AEADBadTagException) {
            throw InvalidBackupPassphraseException()
        } catch (error: GeneralSecurityException) {
            throw BackupFormatException(error.message ?: "Backup decryption failed")
        } finally {
            keyBytes.fill(0)
        }
    }

    @Throws(BackupFormatException::class)
    private fun deriveAesKey(passphraseBytes: ByteArray, salt: ByteArray, iterations: Int): ByteArray =
        try {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(passphraseBytes, "HmacSHA1"))
            val output = ByteArray(AES_KEY_SIZE_BYTES)
            var generated = 0
            var blockIndex = 1
            while (generated < output.size) {
                var u = ByteArray(0)
                var block = ByteArray(0)
                try {
                    mac.update(salt)
                    u = mac.doFinal(int32Bytes(blockIndex))
                    block = u.copyOf()
                    repeat(iterations - 1) {
                        val previousU = u
                        try {
                            u = mac.doFinal(previousU)
                        } finally {
                            previousU.fill(0)
                        }
                        for (index in block.indices) {
                            block[index] = (block[index].toInt() xor u[index].toInt()).toByte()
                        }
                    }
                    val copySize = minOf(block.size, output.size - generated)
                    block.copyInto(output, destinationOffset = generated, startIndex = 0, endIndex = copySize)
                    generated += copySize
                    blockIndex += 1
                } finally {
                    u.fill(0)
                    block.fill(0)
                }
            }
            output
        } catch (error: GeneralSecurityException) {
            throw BackupFormatException(error.message ?: "Backup key derivation failed")
        }

    private fun encodeContainerMetadata(salt: ByteArray, iv: ByteArray): ByteArray =
        ByteWriter().apply {
            writeInt(CONTAINER_MAGIC)
            writeInt(FORMAT_VERSION)
            writeInt(KDF_ID_PBKDF2_HMAC_SHA1)
            writeInt(KDF_ITERATIONS)
            writeInt(CIPHER_ID_AES_256_GCM)
            writeBytesWithLength(salt)
            writeBytesWithLength(iv)
        }.toByteArray()

    private fun passphraseToUtf16BigEndianBytes(passphrase: CharArray): ByteArray {
        val bytes = ByteArray(passphrase.size * 2)
        passphrase.forEachIndexed { index, char ->
            bytes[index * 2] = (char.code ushr 8).toByte()
            bytes[index * 2 + 1] = char.code.toByte()
        }
        return bytes
    }

    private fun int32Bytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private data class BackupContainer(
        val metadata: ByteArray,
        val salt: ByteArray,
        val iv: ByteArray,
        val ciphertext: ByteArray,
    )

    private fun decodeNativeResult(result: ByteArray, allowInvalidPassphrase: Boolean): ByteArray {
        if (result.isEmpty()) throw BackupFormatException("Invalid native backup response")
        val status = result[0].toInt() and 0xff
        val payload = result.copyOfRange(1, result.size)
        return when (status) {
            NativeBackupBridge.STATUS_OK -> payload
            NativeBackupBridge.STATUS_INVALID_PASSPHRASE -> {
                if (allowInvalidPassphrase) throw InvalidBackupPassphraseException()
                throw BackupFormatException("Native backup encryption rejected passphrase")
            }
            NativeBackupBridge.STATUS_FORMAT_ERROR -> {
                val message = String(payload, Charsets.UTF_8).ifBlank { "Invalid backup file" }
                throw BackupFormatException(message)
            }
            else -> {
                val message = String(payload, Charsets.UTF_8).ifBlank { "Native backup failure" }
                throw BackupFormatException(message)
            }
        }
    }

    private class ByteWriter(initialCapacity: Int = 256) {
        private var bytes = ByteArray(initialCapacity)
        private var size = 0

        fun writeInt(value: Int) {
            ensureCapacity(size + Int.SIZE_BYTES)
            bytes[size] = (value ushr 24).toByte()
            bytes[size + 1] = (value ushr 16).toByte()
            bytes[size + 2] = (value ushr 8).toByte()
            bytes[size + 3] = value.toByte()
            size += Int.SIZE_BYTES
        }

        fun writeLong(value: Long) {
            ensureCapacity(size + Long.SIZE_BYTES)
            bytes[size] = (value ushr 56).toByte()
            bytes[size + 1] = (value ushr 48).toByte()
            bytes[size + 2] = (value ushr 40).toByte()
            bytes[size + 3] = (value ushr 32).toByte()
            bytes[size + 4] = (value ushr 24).toByte()
            bytes[size + 5] = (value ushr 16).toByte()
            bytes[size + 6] = (value ushr 8).toByte()
            bytes[size + 7] = value.toByte()
            size += Long.SIZE_BYTES
        }

        fun writeBoolean(value: Boolean) {
            ensureCapacity(size + 1)
            bytes[size] = if (value) 1.toByte() else 0.toByte()
            size += 1
        }

        fun writeBytes(value: ByteArray) {
            ensureCapacity(size + value.size)
            value.copyInto(bytes, destinationOffset = size)
            size += value.size
        }

        fun writeBytesWithLength(value: ByteArray) {
            writeInt(value.size)
            writeBytes(value)
        }

        fun writeNullableLong(value: Long?) {
            writeBoolean(value != null)
            if (value != null) writeLong(value)
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)

        private fun ensureCapacity(required: Int) {
            if (required <= bytes.size) return
            var next = bytes.size
            while (next < required) {
                next = (next * 2).coerceAtMost(MAX_BACKUP_SIZE_BYTES)
                if (next < required && next == MAX_BACKUP_SIZE_BYTES) {
                    throw BackupFormatException("Backup payload is too large")
                }
            }
            bytes = bytes.copyOf(next)
        }
    }

    private class ByteReader(private val bytes: ByteArray) {
        private var offset: Int = 0

        fun readInt(): Int {
            ensureRemaining(Int.SIZE_BYTES, "int")
            val value = ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
            offset += Int.SIZE_BYTES
            return value
        }

        fun readLong(): Long {
            ensureRemaining(Long.SIZE_BYTES, "long")
            val value =
                ((bytes[offset].toLong() and 0xff) shl 56) or
                    ((bytes[offset + 1].toLong() and 0xff) shl 48) or
                    ((bytes[offset + 2].toLong() and 0xff) shl 40) or
                    ((bytes[offset + 3].toLong() and 0xff) shl 32) or
                    ((bytes[offset + 4].toLong() and 0xff) shl 24) or
                    ((bytes[offset + 5].toLong() and 0xff) shl 16) or
                    ((bytes[offset + 6].toLong() and 0xff) shl 8) or
                    (bytes[offset + 7].toLong() and 0xff)
            offset += Long.SIZE_BYTES
            return value
        }

        fun readBoolean(): Boolean {
            ensureRemaining(1, "boolean")
            return bytes[offset++].toInt() != 0
        }

        fun readNullableLong(): Long? =
            if (readBoolean()) readLong() else null

        fun readPositiveInt(label: String): Int {
            val value = readInt()
            if (value < 0) throw BackupFormatException("Invalid $label")
            return value
        }

        fun readExactBytes(size: Int, label: String): ByteArray {
            ensureRemaining(size, label)
            return bytes.copyOfRange(offset, offset + size).also { offset += size }
        }

        fun isEof(): Boolean = offset == bytes.size

        private fun ensureRemaining(required: Int, label: String) {
            if (required < 0 || bytes.size - offset < required) {
                throw BackupFormatException("Truncated backup $label")
            }
        }
    }

    private fun validateItemCount(count: Int, label: String) {
        if (count > MAX_ITEMS) throw BackupFormatException("$label is too large")
    }
}
