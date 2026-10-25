package ai.reopt.djtrot.account

import ai.reopt.djtrot.api.DeviceCredential
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 차량 기기 자격(deviceId·deviceSecret) 보관함.
 * 🔴 자격은 페어링 때 한 번만 온다. 잃으면 다시 연결해야 하고, 새면 남이 그 계정으로 노래를 만든다.
 *    그래서 Android Keystore의 AES-GCM 키로 암호화해 앱 전용 설정에 둔다(키는 기기 밖으로 나가지 않는다).
 */
class DeviceVault(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(): DeviceCredential? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        val sealed = prefs.getString(KEY_SECRET, null) ?: return null
        val secret = runCatching { open(sealed) }.getOrNull() ?: return null.also { clear() }
        return DeviceCredential(id, secret)
    }

    // 🔑 자격은 바로 디스크에 쓴다(commit). 페어링 직후 앱이 꺼져도 연결이 남아야 한다.
    fun write(device: DeviceCredential) {
        prefs.edit(commit = true) { putString(KEY_ID, device.deviceId).putString(KEY_SECRET, seal(device.deviceSecret)) }
    }

    fun clear() {
        prefs.edit(commit = true) { remove(KEY_ID).remove(KEY_SECRET) }
    }

    private fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun open(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
        }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    private companion object {
        const val PREFS = "djtrot_device"
        const val KEY_ID = "device_id"
        const val KEY_SECRET = "device_secret"
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "djtrot.device"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
