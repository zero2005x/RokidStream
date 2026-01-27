package com.rokid.stream.receiver.util

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Utility class for managing app locale/language settings.
 * Supports 13 languages: English, Simplified Chinese, Traditional Chinese,
 * Japanese, Korean, Vietnamese, Thai, French, Spanish, Russian, Ukrainian, Arabic, Italian.
 */
object LocaleManager {

    private const val PREFS_NAME = "locale_prefs"
    private const val KEY_LANGUAGE = "app_language"
    const val SYSTEM_DEFAULT = "system"

    /**
     * Available languages with their locale codes.
     */
    enum class AppLanguage(val code: String, val displayName: String, val nativeName: String) {
        SYSTEM(SYSTEM_DEFAULT, "System Default", "System Default"),
        ENGLISH("en", "English", "English"),
        SIMPLIFIED_CHINESE("zh-CN", "Simplified Chinese", "简体中文"),
        TRADITIONAL_CHINESE("zh-TW", "Traditional Chinese", "繁體中文"),
        JAPANESE("ja", "Japanese", "日本語"),
        KOREAN("ko", "Korean", "한국어"),
        VIETNAMESE("vi", "Vietnamese", "Tiếng Việt"),
        THAI("th", "Thai", "ไทย"),
        FRENCH("fr", "French", "Français"),
        SPANISH("es", "Spanish", "Español"),
        RUSSIAN("ru", "Russian", "Русский"),
        UKRAINIAN("uk", "Ukrainian", "Українська"),
        ARABIC("ar", "Arabic", "العربية"),
        ITALIAN("it", "Italian", "Italiano");

        companion object {
            fun fromCode(code: String): AppLanguage {
                return entries.find { it.code == code } ?: SYSTEM
            }
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Get the currently saved language code.
     */
    fun getSavedLanguage(context: Context): String {
        return getPrefs(context).getString(KEY_LANGUAGE, SYSTEM_DEFAULT) ?: SYSTEM_DEFAULT
    }

    /**
     * Get the currently saved AppLanguage.
     */
    fun getSavedAppLanguage(context: Context): AppLanguage {
        return AppLanguage.fromCode(getSavedLanguage(context))
    }

    /**
     * Save the selected language code.
     */
    fun saveLanguage(context: Context, languageCode: String) {
        getPrefs(context).edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    /**
     * Convert language code to Locale object.
     */
    fun getLocaleFromCode(code: String): Locale? {
        if (code == SYSTEM_DEFAULT) return null
        
        return when {
            code.contains("-") -> {
                val parts = code.split("-")
                Locale(parts[0], parts[1])
            }
            else -> Locale(code)
        }
    }

    /**
     * Apply the saved locale to the context.
     * Call this in attachBaseContext of your Activity or Application.
     */
    fun applyLocale(context: Context): Context {
        val languageCode = getSavedLanguage(context)
        val locale = getLocaleFromCode(languageCode) ?: return context

        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            config.setLocales(LocaleList(locale))
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }

    /**
     * Update the locale configuration for an activity.
     * Call this when the user changes the language.
     */
    fun updateLocale(activity: Activity, languageCode: String) {
        saveLanguage(activity, languageCode)
        
        val locale = getLocaleFromCode(languageCode)
        
        if (locale != null) {
            Locale.setDefault(locale)
            val config = Configuration(activity.resources.configuration)
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                config.setLocales(LocaleList(locale))
            } else {
                @Suppress("DEPRECATION")
                config.locale = locale
            }
            
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
        }
        
        // Recreate the activity to apply changes
        activity.recreate()
    }

    /**
     * Get all available languages.
     */
    fun getAvailableLanguages(): List<AppLanguage> {
        return AppLanguage.entries.toList()
    }
    
    /**
     * Get language from BLE byte code.
     * The byte codes match the enum ordinal values from phone app.
     */
    fun getLanguageFromByteCode(code: Byte): AppLanguage {
        val index = code.toInt()
        return if (index in AppLanguage.entries.indices) {
            AppLanguage.entries[index]
        } else {
            AppLanguage.ENGLISH
        }
    }
    
    /**
     * Apply language from BLE byte code without recreating activity.
     */
    fun applyLanguageFromBle(context: Context, byteCode: Byte) {
        val language = getLanguageFromByteCode(byteCode)
        saveLanguage(context, language.code)
    }
}
