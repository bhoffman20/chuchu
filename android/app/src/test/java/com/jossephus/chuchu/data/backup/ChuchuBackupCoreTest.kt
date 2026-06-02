package com.jossephus.chuchu.data.backup

import com.jossephus.chuchu.model.AuthMethod
import com.jossephus.chuchu.model.HostProfile
import com.jossephus.chuchu.model.SshKey
import com.jossephus.chuchu.model.Transport
import com.jossephus.chuchu.service.backup.ChuchuBackupCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChuchuBackupCoreTest {
    @Test
    fun encryptedBackupRoundTripPreservesAllCurrentFields() {
        val payload = samplePayload()

        val encrypted = ChuchuBackupCodec.encrypt(payload, "correct horse".toCharArray())
        val decrypted = ChuchuBackupCodec.decrypt(encrypted, "correct horse".toCharArray())

        assertEquals(payload, decrypted)
        assertEquals("echo hello", decrypted.hosts.single().postConnectCommand)
        assertFalse(String(encrypted, Charsets.ISO_8859_1).contains("PRIVATE KEY"))
    }

    @Test(expected = InvalidBackupPassphraseException::class)
    fun wrongPassphraseFailsWithoutPayload() {
        val encrypted = ChuchuBackupCodec.encrypt(samplePayload(), "right".toCharArray())

        ChuchuBackupCodec.decrypt(encrypted, "wrong".toCharArray())
    }

    @Test
    fun encryptedBackupsUseFreshSaltAndIv() {
        val payload = samplePayload()

        val first = ChuchuBackupCodec.encrypt(payload, "same passphrase".toCharArray())
        val second = ChuchuBackupCodec.encrypt(payload, "same passphrase".toCharArray())

        assertFalse(first.contentEquals(second))
    }

    @Test
    fun encryptedBackupUsesNativeContainerMetadataOrder() {
        val encrypted = ChuchuBackupCodec.encrypt(samplePayload(), "right".toCharArray())

        assertEquals(0x4348424b, readIntAt(encrypted, 0))
        assertEquals(ChuchuBackupCodec.FORMAT_VERSION, readIntAt(encrypted, 4))
        assertEquals(ChuchuBackupCodec.KDF_ID_PBKDF2_HMAC_SHA1, readIntAt(encrypted, 8))
        assertEquals(ChuchuBackupCodec.KDF_ITERATIONS, readIntAt(encrypted, 12))
        assertEquals(ChuchuBackupCodec.CIPHER_ID_AES_256_GCM, readIntAt(encrypted, 16))
        assertEquals(ChuchuBackupCodec.SALT_SIZE_BYTES, readIntAt(encrypted, 20))
        assertEquals(ChuchuBackupCodec.IV_SIZE_BYTES, readIntAt(encrypted, 40))
    }

    @Test
    fun backupKeyDerivationMatchesNativeUtf16BeVector() {
        val passphraseBytes = byteArrayOf(
            0x00, 0x70,
            0x00, 0x61,
            0x00, 0x73,
            0x00, 0x73,
            0x00, 0x77,
            0x00, 0x6f,
            0x00, 0x72,
            0x00, 0x64,
        )
        val salt = ByteArray(ChuchuBackupCodec.SALT_SIZE_BYTES) { it.toByte() }
        val expected = byteArrayOf(
            0xde.toByte(), 0x7c, 0x12, 0x36, 0x4e, 0xea.toByte(), 0xd1.toByte(),
            0x9a.toByte(), 0xc7.toByte(), 0x06, 0xb0.toByte(), 0xa4.toByte(),
            0xb9.toByte(), 0x88.toByte(), 0xf0.toByte(), 0x60, 0x11, 0xa4.toByte(),
            0x82.toByte(), 0x7d, 0x23, 0x66, 0x98.toByte(), 0xfc.toByte(),
            0xd6.toByte(), 0x5e, 0x57, 0x89.toByte(), 0xdb.toByte(), 0x5a, 0x74,
            0xc7.toByte(),
        )

        assertArrayEquals(expected, deriveAesKeyForTest(passphraseBytes, salt))
    }

    @Test(expected = InvalidBackupPassphraseException::class)
    fun encryptedBackupRejectsMetadataTampering() {
        val encrypted = ChuchuBackupCodec.encrypt(samplePayload(), "right".toCharArray())
        encrypted[24] = (encrypted[24].toInt() xor 0x01).toByte()

        ChuchuBackupCodec.decrypt(encrypted, "right".toCharArray())
    }

    @Test(expected = InvalidBackupPassphraseException::class)
    fun encryptedBackupRejectsCiphertextTampering() {
        val encrypted = ChuchuBackupCodec.encrypt(samplePayload(), "right".toCharArray())
        encrypted[encrypted.lastIndex] = (encrypted[encrypted.lastIndex].toInt() xor 0x01).toByte()

        ChuchuBackupCodec.decrypt(encrypted, "right".toCharArray())
    }

    @Test(expected = BackupFormatException::class)
    fun encryptedBackupRejectsOversizedCiphertextBeforeAllocation() {
        val encrypted = ChuchuBackupCodec.encrypt(samplePayload(), "right".toCharArray())
        writeIntAt(
            encrypted,
            offset = ciphertextSizeOffset(encrypted),
            value = ChuchuBackupCodec.MAX_BACKUP_SIZE_BYTES + 1,
        )

        ChuchuBackupCodec.decrypt(encrypted, "right".toCharArray())
    }

    @Test(expected = BackupFormatException::class)
    fun payloadEncodeRejectsOversizedStrings() {
        val oversizedName = "x".repeat((4 * 1024 * 1024) + 1)

        ChuchuBackupCodec.encodePayload(
            samplePayload().copy(
                keys = listOf(BackupSshKey.fromEntity(sampleKey(name = oversizedName))),
            ),
        )
    }

    @Test
    fun importPlanReusesIdenticalKeysAndRenamesConflictingKeys() {
        val existing = sampleKey(id = 50L, name = "main")
        val conflicting = sampleKey(id = 1L, name = "main", privateKeyPem = "DIFFERENT")
        val identical = BackupSshKey.fromEntity(existing.copy(id = 2L))
        val payload = BackupPayload(
            keys = listOf(identical, BackupSshKey.fromEntity(conflicting)),
            hosts = emptyList(),
        )

        val plan = ChuchuBackupImportPlanner.planImport(
            payload = payload,
            existingKeys = listOf(existing),
            existingHosts = emptyList(),
        )

        assertEquals(1, plan.keyReuseCount)
        assertEquals(1, plan.keyInsertCount)
        val insert = plan.keyActions.filterIsInstance<KeyImportAction.Insert>().single()
        assertEquals("main-2", insert.key.name)
        assertEquals(0L, insert.key.id)
        assertTrue(insert.renamed)
    }

    @Test
    fun importPlanNeverPreservesExportedPrimaryKeys() {
        val payload = BackupPayload(
            keys = listOf(BackupSshKey.fromEntity(sampleKey(id = 10L, name = "new"))),
            hosts = listOf(BackupHostProfile.fromEntity(sampleHost(id = 10L, keyId = 10L))),
        )

        val plan = ChuchuBackupImportPlanner.planImport(
            payload = payload,
            existingKeys = listOf(sampleKey(id = 10L, name = "local")),
            existingHosts = listOf(sampleHost(id = 10L, name = "local-host", keyId = null)),
        )

        val insertKey = plan.keyActions.filterIsInstance<KeyImportAction.Insert>().single()
        val insertHost = plan.hostActions.single()
        assertEquals(0L, insertKey.key.id)
        assertEquals(0L, insertHost.host.id)
        assertEquals("new", insertKey.key.name)
        assertEquals("server", insertHost.host.name)
    }

    @Test
    fun importPlanRemapsHostsToReusedLocalKeyIds() {
        val existing = sampleKey(id = 77L, name = "main")
        val payload = BackupPayload(
            keys = listOf(BackupSshKey.fromEntity(existing.copy(id = 1L))),
            hosts = listOf(BackupHostProfile.fromEntity(sampleHost(id = 2L, keyId = 1L))),
        )

        val plan = ChuchuBackupImportPlanner.planImport(
            payload = payload,
            existingKeys = listOf(existing),
            existingHosts = emptyList(),
        )

        assertEquals(77L, plan.hostActions.single().host.keyId)
        assertNull(plan.hostActions.single().keyActionIndex)
    }

    @Test
    fun importPlanTracksInsertedKeyForDeferredHostRemap() {
        val payload = BackupPayload(
            keys = listOf(BackupSshKey.fromEntity(sampleKey(id = 1L, name = "main"))),
            hosts = listOf(BackupHostProfile.fromEntity(sampleHost(id = 2L, keyId = 1L))),
        )

        val plan = ChuchuBackupImportPlanner.planImport(
            payload = payload,
            existingKeys = emptyList(),
            existingHosts = emptyList(),
        )

        assertNull(plan.hostActions.single().host.keyId)
        assertEquals(0, plan.hostActions.single().keyActionIndex)
    }

    @Test(expected = BackupFormatException::class)
    fun importPlanRejectsMissingKeyReferences() {
        val payload = BackupPayload(
            keys = emptyList(),
            hosts = listOf(BackupHostProfile.fromEntity(sampleHost(id = 2L, keyId = 1L))),
        )

        ChuchuBackupImportPlanner.planImport(
            payload = payload,
            existingKeys = emptyList(),
            existingHosts = emptyList(),
        )
    }

    @Test(expected = BackupFormatException::class)
    fun payloadDecodeRejectsUnknownEnums() {
        val payload = samplePayload().copy(
            hosts = listOf(BackupHostProfile.fromEntity(sampleHost(authMethod = AuthMethod.Key))),
        )
        val encoded = ChuchuBackupCodec.encodePayload(payload)
        replaceLastAscii(encoded, "Key", "Bog")

        ChuchuBackupCodec.decodePayload(encoded)
    }

    @Test(expected = BackupFormatException::class)
    fun payloadDecodeRejectsMalformedBytes() {
        ChuchuBackupCodec.decodePayload(byteArrayOf(1, 2, 3))
    }

    private fun samplePayload(): BackupPayload = BackupPayload(
        keys = listOf(BackupSshKey.fromEntity(sampleKey())),
        hosts = listOf(BackupHostProfile.fromEntity(sampleHost())),
    )

    private fun sampleKey(
        id: Long = 1L,
        name: String = "main",
        privateKeyPem: String = "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----",
    ): SshKey = SshKey(
        id = id,
        name = name,
        algorithm = "ED25519",
        privateKeyPem = privateKeyPem,
        publicKeyOpenSsh = "ssh-ed25519 AAAA test",
        createdAtEpochMs = 1234L,
    )

    private fun sampleHost(
        id: Long = 2L,
        name: String = "server",
        keyId: Long? = 1L,
        authMethod: AuthMethod = AuthMethod.KeyWithPassphrase,
    ): HostProfile = HostProfile(
        id = id,
        name = name,
        host = "example.com",
        port = 2222,
        username = "salem",
        password = "saved-password",
        keyId = keyId,
        keyPassphrase = "key-passphrase",
        transport = Transport.Mosh,
        authMethod = authMethod,
        requireAuthOnConnect = true,
        postConnectCommand = "echo hello",
    )

    private fun ciphertextSizeOffset(bytes: ByteArray): Int {
        var offset = Int.SIZE_BYTES * 5
        val saltSize = readIntAt(bytes, offset)
        offset += Int.SIZE_BYTES + saltSize
        val ivSize = readIntAt(bytes, offset)
        offset += Int.SIZE_BYTES + ivSize
        return offset
    }

    private fun readIntAt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private fun writeIntAt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun deriveAesKeyForTest(passphraseBytes: ByteArray, salt: ByteArray): ByteArray {
        val method = ChuchuBackupCodec::class.java.getDeclaredMethod(
            "deriveAesKey",
            ByteArray::class.java,
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
        )
        method.isAccessible = true
        return method.invoke(
            ChuchuBackupCodec,
            passphraseBytes,
            salt,
            ChuchuBackupCodec.KDF_ITERATIONS,
        ) as ByteArray
    }

    private fun replaceLastAscii(bytes: ByteArray, oldValue: String, newValue: String) {
        val oldBytes = oldValue.toByteArray(Charsets.UTF_8)
        val newBytes = newValue.toByteArray(Charsets.UTF_8)
        require(oldBytes.size == newBytes.size)
        var matchIndex = -1
        for (index in 0..bytes.size - oldBytes.size) {
            if (oldBytes.indices.all { offset -> bytes[index + offset] == oldBytes[offset] }) {
                matchIndex = index
            }
        }
        require(matchIndex >= 0)
        newBytes.copyInto(bytes, destinationOffset = matchIndex)
    }
}
