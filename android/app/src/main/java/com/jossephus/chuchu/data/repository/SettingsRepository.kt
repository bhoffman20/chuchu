package com.jossephus.chuchu.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.jossephus.chuchu.ui.screens.Terminal.TerminalTabMode
import com.jossephus.chuchu.ui.terminal.TerminalAccessoryLayoutStore
import com.jossephus.chuchu.ui.theme.ChuFontOption
import com.jossephus.chuchu.ui.theme.ThemeMode
import com.jossephus.chuchu.ui.terminal.TerminalCustomActionStore
import com.jossephus.chuchu.ui.terminal.TerminalCustomKeyGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("chuchu_settings", Context.MODE_PRIVATE).also {
            // Removed in favor of font-size-driven column count; clean up stale value.
            if (it.contains("terminal_columns")) it.edit().remove("terminal_columns").apply()
        }

    private val _themeName = MutableStateFlow(prefs.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME)
    val themeName: StateFlow<String> = _themeName.asStateFlow()

    private val _fontName = MutableStateFlow(
        ChuFontOption.fromId(prefs.getString(KEY_FONT, DEFAULT_FONT)).id,
    )
    val fontName: StateFlow<String> = _fontName.asStateFlow()

    private val _accessoryLayoutIds = MutableStateFlow(loadAccessoryLayoutIds())
    val accessoryLayoutIds: StateFlow<List<String>> = _accessoryLayoutIds.asStateFlow()

    private val _terminalCustomKeyGroups = MutableStateFlow(loadTerminalCustomKeyGroups())
    val terminalCustomKeyGroups: StateFlow<List<TerminalCustomKeyGroup>> = _terminalCustomKeyGroups.asStateFlow()

    private val _showCustomActionsFab = MutableStateFlow(prefs.getBoolean(KEY_SHOW_CUSTOM_ACTIONS_FAB, true))
    val showCustomActionsFab: StateFlow<Boolean> = _showCustomActionsFab.asStateFlow()

    private val _builtinShortcuts = MutableStateFlow(loadBuiltinShortcuts())
    val builtinShortcuts: StateFlow<Map<String, String>> = _builtinShortcuts.asStateFlow()

    private val _accessoryBarSingleRow = MutableStateFlow(prefs.getBoolean(KEY_ACCESSORY_BAR_SINGLE_ROW, false))
    val accessoryBarSingleRow: StateFlow<Boolean> = _accessoryBarSingleRow.asStateFlow()

    private val _appLockEnabled = MutableStateFlow(prefs.getBoolean(KEY_APP_LOCK_ENABLED, false))
    val appLockEnabled: StateFlow<Boolean> = _appLockEnabled.asStateFlow()

    private val _requireAuthOnConnect = MutableStateFlow(prefs.getBoolean(KEY_REQUIRE_AUTH_ON_CONNECT, false))
    val requireAuthOnConnect: StateFlow<Boolean> = _requireAuthOnConnect.asStateFlow()

    private val _terminalTabMode = MutableStateFlow(
        parseTabMode(prefs.getString(KEY_TAB_MODE, TerminalTabMode.Classic.name)),
    )
    val terminalTabMode: StateFlow<TerminalTabMode> = _terminalTabMode.asStateFlow()

    private val _themeMode = MutableStateFlow(parseThemeMode(prefs.getString(KEY_THEME_MODE, DEFAULT_THEME_MODE.name)))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _lightThemeName = MutableStateFlow(
        prefs.getString(KEY_LIGHT_THEME, DEFAULT_LIGHT_THEME) ?: DEFAULT_LIGHT_THEME,
    )
    val lightThemeName: StateFlow<String> = _lightThemeName.asStateFlow()

    private val _terminalFontSize = MutableStateFlow(
        prefs.getFloat(KEY_TERMINAL_FONT_SIZE, DEFAULT_TERMINAL_FONT_SIZE)
            .coerceIn(MIN_TERMINAL_FONT_SIZE, MAX_TERMINAL_FONT_SIZE),
    )
    val terminalFontSize: StateFlow<Float> = _terminalFontSize.asStateFlow()

    fun setTheme(name: String) {
        prefs.edit().putString(KEY_THEME, name).apply()
        _themeName.value = name
    }

    fun setFont(name: String) {
        val normalized = ChuFontOption.fromId(name).id
        prefs.edit().putString(KEY_FONT, normalized).apply()
        _fontName.value = normalized
    }

    fun setAccessoryLayoutIds(ids: List<String>) {
        val normalized = TerminalAccessoryLayoutStore.normalizeIds(ids)
        prefs.edit().putString(KEY_ACCESSORY_LAYOUT, normalized.joinToString(separator = ",")).apply()
        _accessoryLayoutIds.value = normalized
    }

    fun setTerminalCustomKeyGroups(groups: List<TerminalCustomKeyGroup>) {
        val normalized = TerminalCustomActionStore.normalize(groups)
        val serialized = TerminalCustomActionStore.serialize(normalized)
        prefs.edit().putString(KEY_TERMINAL_CUSTOM_ACTIONS, serialized).apply()
        _terminalCustomKeyGroups.value = normalized
    }

    fun setShowCustomActionsFab(visible: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_CUSTOM_ACTIONS_FAB, visible).apply()
        _showCustomActionsFab.value = visible
    }

    fun setBuiltinShortcuts(shortcuts: Map<String, String>) {
        val serialized = serializeBuiltinShortcuts(shortcuts)
        prefs.edit().putString(KEY_BUILTIN_SHORTCUTS, serialized).apply()
        _builtinShortcuts.value = shortcuts
    }

    fun setAccessoryBarSingleRow(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ACCESSORY_BAR_SINGLE_ROW, enabled).apply()
        _accessoryBarSingleRow.value = enabled
    }

    fun setTerminalTabMode(mode: TerminalTabMode) {
        prefs.edit().putString(KEY_TAB_MODE, mode.name).apply()
        _terminalTabMode.value = mode
    }

    fun setAppLockEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_APP_LOCK_ENABLED, enabled).apply()
        _appLockEnabled.value = enabled
    }

    fun setRequireAuthOnConnect(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REQUIRE_AUTH_ON_CONNECT, enabled).apply()
        _requireAuthOnConnect.value = enabled
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    fun setLightTheme(name: String) {
        prefs.edit().putString(KEY_LIGHT_THEME, name).apply()
        _lightThemeName.value = name
    }

    fun setTerminalFontSize(sizeSp: Float) {
        val clamped = sizeSp.coerceIn(MIN_TERMINAL_FONT_SIZE, MAX_TERMINAL_FONT_SIZE)
        prefs.edit().putFloat(KEY_TERMINAL_FONT_SIZE, clamped).apply()
        _terminalFontSize.value = clamped
    }

    private fun loadAccessoryLayoutIds(): List<String> {
        val stored = prefs.getString(KEY_ACCESSORY_LAYOUT, null)
            ?: return TerminalAccessoryLayoutStore.defaultLayoutIds()
        if (stored.isBlank()) {
            return emptyList()
        }
        return TerminalAccessoryLayoutStore.normalizeIds(
            stored.split(',').map(String::trim).filter(String::isNotEmpty),
        )
    }

    private fun loadTerminalCustomKeyGroups(): List<TerminalCustomKeyGroup> {
        val stored = prefs.getString(KEY_TERMINAL_CUSTOM_ACTIONS, null)
        return TerminalCustomActionStore.parse(stored)
    }

    private fun loadBuiltinShortcuts(): Map<String, String> {
        val stored = prefs.getString(KEY_BUILTIN_SHORTCUTS, null)
        return parseBuiltinShortcuts(stored)
    }

    companion object {
        private const val KEY_THEME = "theme_name"
        private const val KEY_FONT = "font_name"
        private const val KEY_ACCESSORY_LAYOUT = "terminal_accessory_layout"
        private const val KEY_TERMINAL_CUSTOM_ACTIONS = "terminal_custom_actions"
        private const val KEY_SHOW_CUSTOM_ACTIONS_FAB = "show_custom_actions_fab"
        private const val KEY_BUILTIN_SHORTCUTS = "builtin_shortcuts"
        private const val KEY_ACCESSORY_BAR_SINGLE_ROW = "terminal_accessory_bar_single_row"
        private const val KEY_TAB_MODE = "terminal_tab_mode"
        private const val KEY_APP_LOCK_ENABLED = "app_lock_enabled"
        private const val KEY_REQUIRE_AUTH_ON_CONNECT = "require_auth_on_connect"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_LIGHT_THEME = "light_theme_name"
        private const val KEY_TERMINAL_FONT_SIZE = "terminal_font_size_sp"
        const val DEFAULT_THEME = "Catppuccin Mocha"
        const val DEFAULT_LIGHT_THEME = "Catppuccin Latte"
        val DEFAULT_THEME_MODE = ThemeMode.System
        const val DEFAULT_FONT = "jetbrains_mono"
        const val DEFAULT_TERMINAL_FONT_SIZE = 14f
        const val MIN_TERMINAL_FONT_SIZE = 6f
        const val MAX_TERMINAL_FONT_SIZE = 72f

        @Volatile
        private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository {
            return instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
        }

        private fun parseThemeMode(value: String?): ThemeMode =
            ThemeMode.entries.find { it.name == value } ?: DEFAULT_THEME_MODE

        private fun parseTabMode(value: String?): TerminalTabMode =
            try {
                TerminalTabMode.valueOf(value ?: TerminalTabMode.Classic.name)
            } catch (_: IllegalArgumentException) {
                TerminalTabMode.Classic
            }

        private fun parseBuiltinShortcuts(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return DEFAULT_BUILTIN_SHORTCUTS
            val result = mutableMapOf<String, String>()
            raw.split(",").forEach { part ->
                val kv = part.split(":", limit = 2)
                if (kv.size == 2) {
                    val commandId = kv[0].trim()
                    val shortcut = kv[1].trim()
                    if (commandId.isNotEmpty()) {
                        result[commandId] = shortcut
                    }
                }
            }
            return result.ifEmpty { DEFAULT_BUILTIN_SHORTCUTS }
        }

        private fun serializeBuiltinShortcuts(shortcuts: Map<String, String>): String {
            return shortcuts.entries.joinToString(",") { "${it.key}:${it.value}" }
        }

        val DEFAULT_BUILTIN_SHORTCUTS: Map<String, String> = mapOf(
            "tabs" to "t",
            "new_tab" to "n",
            "actions" to "a",
            "settings" to "s",
            "close" to "q",
        )
    }
}
