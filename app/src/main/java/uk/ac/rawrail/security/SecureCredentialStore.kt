package uk.ac.rawrail.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class AuthMode { RDM_API_KEY, LEGACY_BASIC }

data class DarwinConnection(
    val authMode: AuthMode = AuthMode.RDM_API_KEY,
    val apiKey: String = "",
    val username: String = "",
    val password: String = "",
    val boardEndpointTemplate: String =
        "https://api1.raildata.org.uk/1010-live-departure-board-dep1_2/LDBWS/api/20220120/GetDepartureBoard/{crs}",
    val stationListEndpoint: String = "",
    val stationListApiKey: String = "",
    val reasonCodeEndpoint: String = "",
    val staffEndpointTemplate: String = "https://api1.raildata.org.uk/1010-live-departure-board---staff-version1_0/LDBSVWS/api/20220120/GetDepBoardWithDetails/{crs}/{time}",
    val staffApiKey: String = "",
) {
    override fun toString(): String = "DarwinConnection(credentials redacted)"
}

class SecureCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("rawrail_secure_v3", Context.MODE_PRIVATE)
    private val keyAlias = "rawrail_master_v3"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    }

    private fun masterKey(): SecretKey {
        val existing = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, masterKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val packed = ByteBuffer.allocate(4 + iv.size + encrypted.size)
            .putInt(iv.size).put(iv).put(encrypted).array()
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    private fun decrypt(value: String?): String {
        if (value.isNullOrBlank()) return ""
        return runCatching {
            val packed = Base64.decode(value, Base64.NO_WRAP)
            val buffer = ByteBuffer.wrap(packed)
            val ivLength = buffer.int
            require(ivLength in 12..32)
            val iv = ByteArray(ivLength).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, masterKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun save(connection: DarwinConnection) {
        prefs.edit()
            .putString("mode", connection.authMode.name)
            .putString("apiKey", encrypt(connection.apiKey))
            .putString("username", encrypt(connection.username))
            .putString("password", encrypt(connection.password))
            .putString("boardEndpoint", encrypt(connection.boardEndpointTemplate))
            .putString("stationListEndpoint", encrypt(connection.stationListEndpoint))
            .putString("stationListApiKey", encrypt(connection.stationListApiKey))
            .putString("reasonCodeEndpoint", encrypt(connection.reasonCodeEndpoint))
            .putString("staffEndpoint", encrypt(connection.staffEndpointTemplate))
            .putString("staffApiKey", encrypt(connection.staffApiKey))
            .apply()
    }

    fun load(): DarwinConnection {
        val mode = runCatching {
            AuthMode.valueOf(prefs.getString("mode", AuthMode.RDM_API_KEY.name)!!)
        }.getOrDefault(AuthMode.RDM_API_KEY)

        return DarwinConnection(
            authMode = mode,
            apiKey = decrypt(prefs.getString("apiKey", null)),
            username = decrypt(prefs.getString("username", null)),
            password = decrypt(prefs.getString("password", null)),
            boardEndpointTemplate = decrypt(prefs.getString("boardEndpoint", null))
                .ifBlank {
                    "https://api1.raildata.org.uk/1010-live-departure-board-dep1_2/LDBWS/api/20220120/GetDepartureBoard/{crs}"
                },
            stationListEndpoint = decrypt(prefs.getString("stationListEndpoint", null)),
            stationListApiKey = decrypt(prefs.getString("stationListApiKey", null)),
            reasonCodeEndpoint = decrypt(prefs.getString("reasonCodeEndpoint", null)),
            staffEndpointTemplate = decrypt(prefs.getString("staffEndpoint", null)).ifBlank { DarwinConnection().staffEndpointTemplate },
            staffApiKey = decrypt(prefs.getString("staffApiKey", null)),
        )
    }

    fun isConfigured(): Boolean {
        val c = load()
        return c.staffApiKey.isNotBlank() && c.staffEndpointTemplate.contains("{crs}")
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}
