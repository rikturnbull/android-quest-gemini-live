package uk.co.controlz.live.services

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object SettingsService {
    private const val TAG: String = "SettingsService"

    private var initialized = false
    private var cache = HashMap<SettingsKey, Any>()

    private lateinit var prefs: SharedPreferences

    fun initialize(context: Context) {
        if (initialized) {
            return
        }
        prefs = context.getSharedPreferences("settings_preferences", Context.MODE_PRIVATE)
        initialized = true
    }

    fun <T : Any> get(key: SettingsKey, default: T): T {
        val cachedValue = cache[key]

        if (cachedValue != null && cachedValue::class == default::class) {
            @Suppress("UNCHECKED_CAST")
            return cachedValue as T
        }

        val newValue =
            when (default) {
                is String -> prefs.getString(key.value, default)
                is Int -> prefs.getInt(key.value, default)
                is Boolean -> prefs.getBoolean(key.value, default)
                is Long -> prefs.getLong(key.value, default)
                is Float -> prefs.getFloat(key.value, default)
                else -> throw Exception("Unsupported value type ${default::class.simpleName}")
            }
        cache[key] = newValue!!

        @Suppress("UNCHECKED_CAST")
        return newValue as T
    }

    fun <T : Any> set(key: SettingsKey, value: T) {
        prefs.edit {
            when (value) {
                is String -> putString(key.value, value)
                is Int -> putInt(key.value, value)
                is Boolean -> putBoolean(key.value, value)
                is Long -> putLong(key.value, value)
                is Float -> putFloat(key.value, value)
                else -> throw Exception("Unsupported value type ${value::class.simpleName}")
            }
        }

        cache[key] = value
    }
}