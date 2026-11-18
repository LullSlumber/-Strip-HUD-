package com.lullslumber.fusionstrip.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.lullslumber.fusionstrip.App
import com.lullslumber.fusionstrip.bridge.SettingsChannel
import com.lullslumber.fusionstrip.constant.Constants
import com.lullslumber.fusionstrip.ui.theme.FusionStripTheme
import com.lullslumber.fusionstrip.utils.LogUtils
import com.lullslumber.fusionstrip.utils.RootShell
import io.github.libxposed.service.XposedService
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FusionStrip 设置页 · WebView 版（0.5.0 起）。
 *
 * 界面 = assets/webui/index.html（MD3 风格，JS 侧 FSBridge 桥接），
 * Compose 只保留 AppPickerScreen——几百个应用的图标/搜索/长列表
 * 原生做最省事，HTML 里点「选择应用」通过桥切过来。
 *
 * 数据层与 0.4.2 完全一致：本地 prefs + RemotePreferences +
 * Settings.Global 三通道写入，SystemUI 每秒轮询热更。
 * 桥的键路由：
 *   strip_* / mod_clock_24h / app_filter / app_whitelist /
 *   update_interval_ms / wake_interval_sec → perf 配置管道（写完 publish）
 *   app_theme / dynamic_color / fs_seed    → 本地 "ui" prefs（只影响界面）
 */
class MainActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- UI 状态 ----
    private var showAppPicker by mutableStateOf(false)
    private var whitelist by mutableStateOf<Set<String>>(emptySet())
    private var themeMode by mutableStateOf(THEME_FOLLOW_SYSTEM)
    private var dynamicColor by mutableStateOf(true)
    private var versionName by mutableStateOf("")

    private var webView: WebView? = null

    private val uiPrefs by lazy { getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (t: Throwable) {
            ""
        }
        loadSettings()
        buildWebView()
        setContent {
            val dark = when (themeMode) {
                THEME_LIGHT -> false
                THEME_DARK -> true
                else -> isSystemInDarkTheme()
            }
            FusionStripTheme(darkTheme = dark, dynamic = dynamicColor) {
                // enableEdgeToEdge 后状态栏/导航栏透明，WebView 又被 systemBarsPadding
                // 推离栏区，栏后露出的是黑色窗口底——用页面同款 surface 色垫底，
                // 栏区域就和网页融为一体了（0.5.1 修「状态栏底栏黑」）
                val pageBg = if (showAppPicker) {
                    androidx.compose.material3.MaterialTheme.colorScheme.background
                } else {
                    pageSurface(dark)
                }
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize().background(pageBg)
                ) {
                    if (showAppPicker) {
                        AppPickerScreen(
                            modifier = Modifier.fillMaxSize(),
                            selected = whitelist,
                            onToggle = { pkg, checked -> toggleWhitelist(pkg, checked) },
                            onClearAll = { saveWhitelist(emptySet()) },
                            onBack = { closePicker() }
                        )
                    } else {
                        AndroidView(
                            factory = { webView!! },
                            modifier = Modifier.fillMaxSize().systemBarsPadding()
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        webView?.destroy()
        webView = null
    }

    // ---------------- WebView ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView() {
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            addJavascriptInterface(WebBridge(), "FSAndroid")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (versionName.isNotEmpty()) {
                        view.evaluateJavascript(
                            "document.querySelectorAll('.ver').forEach(function(e){e.textContent='v$versionName'})",
                            null
                        )
                    }
                }
            }
            loadUrl("file:///android_asset/webui/index.html")
        }
    }

    /** 从应用选择器返回：重读白名单并让页面整体刷新 */
    private fun closePicker() {
        showAppPicker = false
        whitelist = readWhitelist()
        mainHandler.post {
            webView?.evaluateJavascript("window.refreshAll && window.refreshAll()", null)
        }
    }

    // ---------------- JS 桥（WebBridge） ----------------

    /**
     * 键路由注册表：
     *   b = Boolean，f = Float，i = Int，s = String（白名单）
     * ui 键（app_theme / dynamic_color / fs_seed）不进横条管道。
     */
    private fun keyType(key: String): Char = when (key) {
        Constants.Remote.KEY_SHOW_FPS,
        Constants.Remote.KEY_SHOW_GRAPH,
        Constants.Remote.KEY_SHOW_GPU,
        Constants.Remote.KEY_SHOW_CPU,
        Constants.Remote.KEY_SHOW_RAM,
        Constants.Remote.KEY_SHOW_BAT,
        Constants.Remote.KEY_SHOW_CLOCK,
        Constants.Remote.KEY_SHOW_CPU_TEMP,
        Constants.Remote.KEY_SHOW_GPU_TEMP,
        Constants.Remote.KEY_SHOW_BAT_TEMP,
        Constants.Remote.KEY_SHOW_PWR,
        Constants.Remote.KEY_SHOW_BAT_TIME,
        Constants.Remote.KEY_SHOW_MODEL,
        Constants.Modules.KEY_APP_FILTER -> 'b'

        Constants.Remote.KEY_FONT_SCALE,
        Constants.Remote.KEY_BG_ALPHA,
        Constants.Remote.KEY_LINE_INTENSITY -> 'f'

        Constants.Remote.KEY_LINE_COLOR,
        Constants.Modules.KEY_UPDATE_INTERVAL,
        Constants.Modules.KEY_WAKE_INTERVAL -> 'i'

        Constants.Modules.KEY_APP_WHITELIST -> 's'

        else -> '?'
    }

    private fun isUiKey(key: String) =
        key == "app_theme" || key == "dynamic_color" || key == "fs_seed"

    private fun bridgeGet(key: String): String? {
        if (isUiKey(key)) {
            return when (key) {
                "app_theme" -> uiPrefs.getInt(KEY_UI_THEME_MODE, THEME_FOLLOW_SYSTEM).toString()
                "dynamic_color" -> uiPrefs.getBoolean(KEY_UI_DYNAMIC_COLOR, true).toString()
                else -> uiPrefs.getString(KEY_UI_SEED, null)
            }
        }
        return when (keyType(key)) {
            'b' -> readBool(key, defaultBool(key)).toString()
            'f' -> readFloat(key, defaultFloat(key)).toString()
            'i' -> readInt(key, defaultInt(key)).toString()
            's' -> readWhitelist().joinToString(Constants.Modules.WHITELIST_SEPARATOR)
            else -> null
        }
    }

    private fun bridgeSet(key: String, value: String) {
        when (key) {
            "app_theme" -> {
                val m = (value.toIntOrNull() ?: THEME_FOLLOW_SYSTEM).coerceIn(0, 2)
                uiPrefs.edit().putInt(KEY_UI_THEME_MODE, m).apply()
                mainHandler.post { themeMode = m }
            }
            "dynamic_color" -> {
                val d = value == "true"
                uiPrefs.edit().putBoolean(KEY_UI_DYNAMIC_COLOR, d).apply()
                mainHandler.post { dynamicColor = d }
            }
            "fs_seed" -> uiPrefs.edit().putString(KEY_UI_SEED, value).apply()
            else -> when (keyType(key)) {
                'b' -> saveSwitch(key, value == "true")
                'f' -> value.toFloatOrNull()?.let { saveFloat(key, it) }
                // 颜色键是 ARGB 十进制（如 4294958413 = 0xFFFFB74D），超出 Int 上限，
                // 必须 Long 中转截断——toIntOrNull 会返回 null 导致颜色永远写不进去
                'i' -> value.toLongOrNull()?.toInt()?.let { n ->
                    val v = when (key) {
                        Constants.Modules.KEY_UPDATE_INTERVAL -> n.coerceIn(500, 3000)
                        Constants.Modules.KEY_WAKE_INTERVAL -> n.coerceIn(0, Constants.Modules.WAKE_INTERVAL_MAX_SEC)
                        else -> n
                    }
                    saveInt(key, v)
                }
                // 白名单不走 HTML 写入：由原生 AppPicker 维护，避免两个编辑入口打架
                's' -> {}
            }
        }
    }

    private fun defaultBool(key: String) =
        key != Constants.Modules.KEY_APP_FILTER // 9 个内容开关 + 24h 默认 true，app_filter 默认 false

    private fun defaultFloat(key: String) = when (key) {
        Constants.Remote.KEY_FONT_SCALE -> 1.0f
        Constants.Remote.KEY_BG_ALPHA -> 0.63f
        else -> 0.5f // 强调线强度
    }

    private fun defaultInt(key: String) = when (key) {
        Constants.Remote.KEY_LINE_COLOR -> 0xFFFFB74D.toInt()
        Constants.Modules.KEY_UPDATE_INTERVAL -> Constants.Config.UPDATE_INTERVAL_MS
        else -> Constants.Modules.WAKE_INTERVAL_DEFAULT_SEC
    }

    private inner class WebBridge {
        @JavascriptInterface
        fun get(key: String): String? = bridgeGet(key)

        @JavascriptInterface
        fun set(key: String, value: String) = bridgeSet(key, value)

        @JavascriptInterface
        fun status(): String {
            val connected = App.getXposedService() != null
            val freq = readInt(Constants.Remote.KEY_GPU_FREQ_MHZ, 0)
            val usage = readInt(Constants.Remote.KEY_GPU_USAGE_PERCENT, -1)
            val at = readLong(Constants.Remote.KEY_UPDATED_AT, 0L)
            val last = if (at > 0) {
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
            } else {
                "从未"
            }
            return JSONObject()
                .put("connected", connected)
                .put("gpuFreq", if (freq > 0) "$freq MHz" else "-- MHz")
                .put("gpuUsage", if (usage >= 0) "$usage %" else "-- %")
                .put("last", last)
                .put("version", versionName)
                .toString()
        }

        @JavascriptInterface
        fun pickApps() {
            mainHandler.post { showAppPicker = true }
        }

        @JavascriptInterface
        fun restart() = restartSystemUi()
    }

    // ---------------- 数据 / 持久化（与 0.4.2 一致） ----------------

    /** root 重启 SystemUI：killall 失败再试 pkill（toybox/busybox 覆盖面不同） */
    private fun restartSystemUi() {
        Toast.makeText(this, "正在重启 SystemUI…", Toast.LENGTH_SHORT).show()
        Thread {
            val sh = RootShell.get()
            val out = sh.execLines(
                "killall com.android.systemui || pkill -f com.android.systemui", 3)
            mainHandler.post {
                if (out == null) {
                    Toast.makeText(this, "执行失败：没有可用的 root shell", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "已发送重启命令，等几秒后横条会重新出现", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun toggleWhitelist(pkg: String, checked: Boolean) {
        val next = whitelist.toMutableSet()
        if (checked) next.add(pkg) else next.remove(pkg)
        saveWhitelist(next)
    }

    private fun saveWhitelist(packages: Set<String>) {
        whitelist = packages
        val raw = packages.joinToString(Constants.Modules.WHITELIST_SEPARATOR)
        getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(Constants.Modules.KEY_APP_WHITELIST, raw).apply()
        writeRemote { it.edit().putString(Constants.Modules.KEY_APP_WHITELIST, raw).apply() }
        publishSettings()
    }

    /** 读初始值：FPS 来源刷成高通预设（0.4.0 起），配置整表发布一次 */
    private fun loadSettings() {
        applyFpsPreset(FPS_PRESET_QUALCOMM)
        publishSettings()
        loadUiPrefs()
    }

    private fun applyFpsPreset(preset: Int) {
        val flags = Constants.Modules.FpsSource.PRESETS[preset]
        for (i in flags.indices) {
            saveSwitchRaw(Constants.Modules.FpsSource.KEYS[i], flags[i])
        }
    }

    private fun loadUiPrefs() {
        themeMode = uiPrefs.getInt(KEY_UI_THEME_MODE, THEME_FOLLOW_SYSTEM)
        dynamicColor = uiPrefs.getBoolean(KEY_UI_DYNAMIC_COLOR, true)
    }

    /**
     * HTML 页面同款 surface 色（对应 index.html 的 --surface）：
     * 深色 hsl(h 14% 6%) / 浅色 hsl(h 28% 98.5%)，h 取当前种子色相。
     * 用于垫在系统栏后面，让透明状态栏/导航栏和网页融为一体。
     */
    private fun pageSurface(dark: Boolean): androidx.compose.ui.graphics.Color {
        val h = SEED_HUES[uiPrefs.getString(KEY_UI_SEED, "blue")] ?: 218f
        return androidx.compose.ui.graphics.Color(
            androidx.core.graphics.ColorUtils.HSLToColor(
                floatArrayOf(
                    h,
                    if (dark) 0.14f else 0.28f,
                    if (dark) 0.06f else 0.985f
                )
            )
        )
    }

    /** 只写不推：给批量写来源开关用，最后一并 publish 一次 */
    private fun saveSwitchRaw(key: String, value: Boolean) {
        getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(key, value).apply()
        writeRemote { it.edit().putBoolean(key, value).apply() }
    }

    private fun writeRemote(block: (android.content.SharedPreferences) -> Unit) {
        val svc: XposedService? = App.getXposedService()
        if (svc != null) {
            try {
                block(svc.getRemotePreferences(Constants.Remote.PREFS_NAME))
            } catch (e: Exception) {
                LogUtils.e("write remote prefs failed", e)
            }
        }
    }

    private fun saveSwitch(key: String, value: Boolean) {
        getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(key, value).apply()
        writeRemote { it.edit().putBoolean(key, value).apply() }
        publishSettings()
    }

    private fun saveFloat(key: String, value: Float) {
        getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat(key, value).apply()
        writeRemote { it.edit().putFloat(key, value).apply() }
        publishSettings()
    }

    private fun saveInt(key: String, value: Int) {
        getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(key, value).apply()
        writeRemote { it.edit().putInt(key, value).apply() }
        publishSettings()
    }

    /** root 整表推 Settings.Global（第三通道），后台线程执行 */
    private fun publishSettings() {
        Thread { SettingsChannel.publish(applicationContext) }.start()
    }

    private fun readBool(key: String, def: Boolean): Boolean {
        val svc = App.getXposedService()
        if (svc != null) {
            try {
                val p = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                if (p.contains(key)) return p.getBoolean(key, def)
            } catch (e: Exception) {
                LogUtils.w("read $key from remote failed", e)
            }
        }
        return getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(key, def)
    }

    private fun readFloat(key: String, def: Float): Float {
        val svc = App.getXposedService()
        if (svc != null) {
            try {
                val p = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                if (p.contains(key)) return p.getFloat(key, def)
            } catch (e: Exception) {
                LogUtils.w("read $key from remote failed", e)
            }
        }
        return getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
            .getFloat(key, def)
    }

    private fun readInt(key: String, def: Int): Int {
        val svc = App.getXposedService()
        if (svc != null) {
            try {
                val p = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                if (p.contains(key)) return p.getInt(key, def)
            } catch (e: Exception) {
                LogUtils.w("read $key from remote failed", e)
            }
        }
        return getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(key, def)
    }

    private fun readLong(key: String, def: Long): Long {
        val svc = App.getXposedService()
        if (svc != null) {
            try {
                val p = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                if (p.contains(key)) return p.getLong(key, def)
            } catch (e: Exception) {
                LogUtils.w("read $key from remote failed", e)
            }
        }
        return getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(key, def)
    }

    private fun readWhitelist(): Set<String> {
        val key = Constants.Modules.KEY_APP_WHITELIST
        var raw: String? = null
        val svc = App.getXposedService()
        if (svc != null) {
            try {
                val p = svc.getRemotePreferences(Constants.Remote.PREFS_NAME)
                if (p.contains(key)) raw = p.getString(key, "")
            } catch (e: Exception) {
                LogUtils.w("read whitelist from remote failed", e)
            }
        }
        if (raw == null) {
            raw = getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(key, "")
        }
        return raw?.split(Constants.Modules.WHITELIST_SEPARATOR)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    companion object {
        // ---- 界面主题（App 自身，本地 prefs，不进横条配置管道） ----
        private const val UI_PREFS_NAME = "ui"
        private const val KEY_UI_THEME_MODE = "theme_mode"
        private const val KEY_UI_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_UI_SEED = "seed"
        private const val THEME_FOLLOW_SYSTEM = 0
        private const val THEME_LIGHT = 1
        private const val THEME_DARK = 2

        /** 种子色相表，与 index.html 的 SEEDS 一一对应 */
        private val SEED_HUES = mapOf(
            "blue" to 218f, "violet" to 264f, "teal" to 184f,
            "green" to 148f, "amber" to 32f, "rose" to 342f
        )

        /** Constants.Modules.FpsSource.PRESETS 里高通预设的下标 */
        private const val FPS_PRESET_QUALCOMM = 1
    }
}
