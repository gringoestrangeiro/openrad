package com.openrad.mobile.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.openrad.mobile.engine.Identity
import com.openrad.mobile.engine.IdentityStore
import com.openrad.mobile.model.AppSettings
import com.openrad.mobile.model.JoinConfiguration

/**
 * Identity in Android Keystore-backed [EncryptedSharedPreferences]. The
 * credential is never written in clear text nor logged. If the Keystore is
 * unavailable the store fails closed instead of falling back to plain storage.
 */
class EncryptedIdentityStore(context: Context) : IdentityStore {
    private val prefs: SharedPreferences by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun load(): Identity? {
        val text = prefs.getString(KEY, null) ?: return null
        return try {
            Codecs.decodeIdentity(text)
        } catch (e: Exception) {
            // Keep the unreadable value for repair; never register a second device silently.
            throw IllegalStateException("stored identity is unreadable; reset it in Settings to register again", e)
        }
    }

    override fun save(identity: Identity) {
        check(prefs.edit().putString(KEY, Codecs.encodeIdentity(identity)).commit()) { "could not save identity" }
    }

    override fun clear() {
        prefs.edit().remove(KEY).commit()
    }

    companion object {
        private const val FILE = "openrad_identity"
        private const val KEY = "identity"
    }
}

/** Settings, favorites and join configurations (no secrets). Corrupt values are kept for repair. */
class PreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("openrad_preferences", Context.MODE_PRIVATE)

    private fun <T> read(key: String, default: T, decode: (String) -> T): T {
        val raw = prefs.getString(key, null) ?: return default
        return try {
            decode(raw)
        } catch (e: Exception) {
            Log.w(TAG, "preference '$key' is corrupt; kept as '$key.corrupt'")
            prefs.edit().putString("$key.corrupt", raw).remove(key).apply()
            default
        }
    }

    fun settings(): AppSettings = read(SETTINGS, AppSettings(), Codecs::decodeSettings)

    fun saveSettings(value: AppSettings) = prefs.edit().putString(SETTINGS, Codecs.encodeSettings(value)).apply()

    fun favorites(): Set<String> = read(FAVORITES, emptySet(), Codecs::decodeFavorites)

    fun saveFavorites(value: Set<String>) = prefs.edit().putString(FAVORITES, Codecs.encodeFavorites(value)).apply()

    fun configurations(): List<JoinConfiguration> = read(CONFIGS, emptyList(), Codecs::decodeConfigurations)

    fun saveConfigurations(value: List<JoinConfiguration>) =
        prefs.edit().putString(CONFIGS, Codecs.encodeConfigurations(value)).apply()

    fun hasCorruptValues(): Boolean = prefs.all.keys.any { it.endsWith(".corrupt") }

    companion object {
        private const val TAG = "OpenRad"
        private const val SETTINGS = "settings"
        private const val FAVORITES = "favorites"
        private const val CONFIGS = "join_configurations"
    }
}
