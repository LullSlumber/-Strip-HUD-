package com.lullslumber.fusionstrip.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lullslumber.fusionstrip.App
import com.lullslumber.fusionstrip.bridge.SettingsChannel
import com.lullslumber.fusionstrip.constant.Constants
import com.lullslumber.fusionstrip.ui.theme.FusionStripTheme
import com.lullslumber.fusionstrip.ui.theme.MdThemeOnSurface
import com.lullslumber.fusionstrip.ui.theme.MdThemeOnSurfaceVariant
import com.lullslumber.fusionstrip.ui.theme.MdThemePrimary
import com.lullslumber.fusionstrip.utils.LogUtils
import io.github.libxposed.service.XposedService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FusionStrip 设置页（M3 重写）。
 *
 * 只服务 Strip 横条：内容开关、外观、显示时机（仅应用过滤）、数据采集。
 * NexusFloat 时代的监视条模块开关、统计页、帧记录全部移除。
 *
 * 写入走三通道复用：本地 prefs + RemotePreferences（模块进程被注入时）+
 * Settings.Global（root，SettingsChannel.publish 整表推送）。SystemUI 侧
 * 每秒轮询，改动约 1 秒内热更生效，无需重启。
 */
class MainActivity : ComponentActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- Strip 内容开关 ----
    private var showFps by mutableStateOf(true)
    private var showGraph by mutableStateOf(true)
    private var showLow1 by mutableStateOf(true)
    private var showGpu by mutableStateOf(true)
    private var showCpu by mutableStateOf(true)
    private var showRam by mutableStateOf(true)
    private var showBat by mutableStateOf(true)
    private var showClock by mutableStateOf(true)

    // ---- 外观 ----
    private var fontScale by mutableStateOf(1.0f)
    private var bgAlpha by mutableStateOf(0.63f)
    private var clock24h by mutableStateOf(true)

    // ---- 显示时机（唯一逻辑：应用过滤） ----
    private var appFilterEnabled by mutableStateOf(false)
    private var whitelist by mutableStateOf<Set<String>>(emptySet())
    private var showAppPicker by mutableStateOf(false)

    // ---- 数据 ----
    private val fpsSourceFlags = mutableStateListOf<Boolean>()
    private var updateIntervalMs by mutableStateOf(Constants.Config.UPDATE_INTERVAL_MS)
    private var wakeIntervalSec by mutableStateOf(Constants.Modules.WAKE_INTERVAL_DEFAULT_SEC)

    // ---- 状态卡 ----
    private var xposedConnected by mutableStateOf(false)
    private var gpuFreqText by mutableStateOf("-- MHz")
    private var gpuUsageText by mutableStateOf("-- %")
    private var lastUpdateText by mutableStateOf("从未")

    private val statusPoll = object : Runnable {
        override fun run() {
            refreshStatus()
            mainHandler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        loadSettings()
        setContent {
            FusionStripTheme {
                if (showAppPicker) {
                    AppPickerScreen(
                        modifier = Modifier.fillMaxSize(),
                        selected = whitelist,
                        onToggle = { pkg, checked -> toggleWhitelist(pkg, checked) },
                        onClearAll = { saveWhitelist(emptySet()) },
                        onBack = { showAppPicker = false }
                    )
                } else {
                    mainScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        mainHandler.post(statusPoll)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(statusPoll)
    }

    // ---------------- UI ----------------

    @Composable
    private fun mainScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "FusionStrip",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = MdThemePrimary
            )
            statusCard()
            contentCard()
            appearanceCard()
            displayCard()
            dataCard()
            Text(
                "改动约 1 秒内生效，无需重启。部分参数改动后若横条未更新，重启 SystemUI 试试。",
                fontSize = 12.sp,
                color = MdThemeOnSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    @Composable
    private fun statusCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("模块状态", fontWeight = FontWeight.SemiBold, color = MdThemeOnSurface)
                statusRow("LSPosed 服务", if (xposedConnected) "已连接" else "未连接（GPU 采集走保底通道）")
                statusRow("GPU 频率", gpuFreqText)
                statusRow("GPU 占用", gpuUsageText)
                statusRow("数据更新", lastUpdateText)
            }
        }
    }

    @Composable
    private fun statusRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, fontSize = 14.sp, color = MdThemeOnSurfaceVariant)
            Text(value, fontSize = 14.sp, color = MdThemeOnSurface)
        }
    }

    @Composable
    private fun contentCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("横条内容", fontWeight = FontWeight.SemiBold, color = MdThemeOnSurface)
                SwitchRow("FPS + 帧时间", showFps) { showFps = it; saveSwitch(Constants.Remote.KEY_SHOW_FPS, it) }
                SwitchRow("帧率历史图", showGraph) { showGraph = it; saveSwitch(Constants.Remote.KEY_SHOW_GRAPH, it) }
                SwitchRow("1% low", showLow1) { showLow1 = it; saveSwitch(Constants.Remote.KEY_SHOW_LOW1, it) }
                SwitchRow("GPU 占用", showGpu) { showGpu = it; saveSwitch(Constants.Remote.KEY_SHOW_GPU, it) }
                SwitchRow("CPU 占用", showCpu) { showCpu = it; saveSwitch(Constants.Remote.KEY_SHOW_CPU, it) }
                SwitchRow("内存占用", showRam) { showRam = it; saveSwitch(Constants.Remote.KEY_SHOW_RAM, it) }
                SwitchRow("电池（温度/功率/续航）", showBat) { showBat = it; saveSwitch(Constants.Remote.KEY_SHOW_BAT, it) }
                SwitchRow("时钟", showClock) { showClock = it; saveSwitch(Constants.Remote.KEY_SHOW_CLOCK, it) }
            }
        }
    }

    @Composable
    private fun appearanceCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("外观", fontWeight = FontWeight.SemiBold, color = MdThemeOnSurface)
                SliderRow(
                    label = "字号倍率",
                    valueText = String.format(Locale.US, "%.2f×", fontScale),
                    value = fontScale,
                    onValueChange = { fontScale = it },
                    onValueChangeFinished = { saveFloat(Constants.Remote.KEY_FONT_SCALE, fontScale) },
                    range = 0.75f..1.5f
                )
                SliderRow(
                    label = "背景不透明度",
                    valueText = String.format(Locale.US, "%.0f%%", bgAlpha * 100f),
                    value = bgAlpha,
                    onValueChange = { bgAlpha = it },
                    onValueChangeFinished = { saveFloat(Constants.Remote.KEY_BG_ALPHA, bgAlpha) },
                    range = 0f..0.95f
                )
                SwitchRow("24 小时制", clock24h) { clock24h = it; saveSwitch(KEY_CLOCK_24H, it) }
            }
        }
    }

    @Composable
    private fun displayCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("显示", fontWeight = FontWeight.SemiBold, color = MdThemeOnSurface)
                SwitchRow(
                    "仅选定应用显示",
                    appFilterEnabled,
                    subtitle = if (appFilterEnabled)
                        "已选 ${whitelist.size} 个应用" + if (whitelist.isEmpty()) "（未选任何应用时横条不显示）" else ""
                    else "关：所有界面显示"
                ) {
                    appFilterEnabled = it
                    saveSwitch(Constants.Modules.KEY_APP_FILTER, it)
                }
                if (appFilterEnabled) {
                    OutlinedButton(
                        onClick = { showAppPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("选择应用")
                    }
                }
            }
        }
    }

    @Composable
    private fun dataCard() {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("数据", fontWeight = FontWeight.SemiBold, color = MdThemeOnSurface)

                // FPS 来源预设：一键填好常用组合，之后可逐个微调
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Constants.Modules.FpsSource.PRESET_LABELS.forEachIndexed { i, label ->
                        OutlinedButton(onClick = { applyFpsPreset(i) }) {
                            Text(label)
                        }
                    }
                }
                Constants.Modules.FpsSource.LABELS.forEachIndexed { i, label ->
                    if (i < fpsSourceFlags.size) {
                        SwitchRow(label, fpsSourceFlags[i]) { checked ->
                            fpsSourceFlags[i] = checked
                            saveSwitch(Constants.Modules.FpsSource.KEYS[i], checked)
                        }
                    }
                }

                SliderRow(
                    label = "刷新周期",
                    valueText = "${updateIntervalMs} ms",
                    value = updateIntervalMs.toFloat(),
                    onValueChange = { updateIntervalMs = (it / 100).toInt() * 100 },
                    onValueChangeFinished = {
                        saveInt(Constants.Modules.KEY_UPDATE_INTERVAL, updateIntervalMs)
                    },
                    range = 500f..3000f
                )
                SliderRow(
                    label = "后台唤醒间隔",
                    valueText = if (wakeIntervalSec <= 0) "关闭" else "${wakeIntervalSec}s",
                    value = wakeIntervalSec.toFloat(),
                    onValueChange = { wakeIntervalSec = (it / 5).toInt() * 5 },
                    onValueChangeFinished = {
                        saveInt(Constants.Modules.KEY_WAKE_INTERVAL, wakeIntervalSec)
                    },
                    range = 0f..Constants.Modules.WAKE_INTERVAL_MAX_SEC.toFloat()
                )
            }
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, fontSize = 15.sp, color = MdThemeOnSurface)
                if (!subtitle.isNullOrEmpty()) {
                    Text(subtitle, fontSize = 12.sp, color = MdThemeOnSurfaceVariant)
                }
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }

    @Composable
    private fun SliderRow(
        label: String,
        valueText: String,
        value: Float,
        onValueChange: (Float) -> Unit,
        onValueChangeFinished: () -> Unit,
        range: ClosedFloatingPointRange<Float>
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, fontSize = 15.sp, color = MdThemeOnSurface)
                Text(valueText, fontSize = 14.sp, color = MdThemePrimary)
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = range
            )
        }
    }

    // ---------------- 数据 / 持久化 ----------------

    private fun refreshStatus() {
        xposedConnected = App.getXposedService() != null
        val freq = readInt(Constants.Remote.KEY_GPU_FREQ_MHZ, 0)
        val usage = readInt(Constants.Remote.KEY_GPU_USAGE_PERCENT, -1)
        gpuFreqText = if (freq > 0) "$freq MHz" else "-- MHz"
        gpuUsageText = if (usage >= 0) "$usage %" else "-- %"
        val at = readLong(Constants.Remote.KEY_UPDATED_AT, 0L)
        lastUpdateText = if (at > 0) {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))
        } else {
            "从未"
        }
    }

    private fun applyFpsPreset(preset: Int) {
        val flags = Constants.Modules.FpsSource.PRESETS[preset]
        for (i in flags.indices) {
            fpsSourceFlags[i] = flags[i]
            saveSwitch(Constants.Modules.FpsSource.KEYS[i], flags[i])
        }
    }

    private fun toggleWhitelist(pkg: String, checked: Boolean) {
        val next = whitelist.toMutableSet()
        if (checked) next.add(pkg) else next.remove(pkg)
        saveWhitelist(next)
    }

    private fun saveWhitelist(packages: Set<String>) {
        whitelist = packages
        val raw = packages.joinToString(Constants.Modules.WHITELIST_SEPARATOR)
        val localPrefs = getSharedPreferences(Constants.Remote.PREFS_NAME, Context.MODE_PRIVATE)
        localPrefs.edit().putString(Constants.Modules.KEY_APP_WHITELIST, raw).apply()
        writeRemote { it.edit().putString(Constants.Modules.KEY_APP_WHITELIST, raw).apply() }
        publishSettings()
    }

    /** 读初始值：键不存在时用默认值，不写回（保持「未设置」语义给读侧兜底） */
    private fun loadSettings() {
        showFps = readBool(Constants.Remote.KEY_SHOW_FPS, true)
        showGraph = readBool(Constants.Remote.KEY_SHOW_GRAPH, true)
        showLow1 = readBool(Constants.Remote.KEY_SHOW_LOW1, true)
        showGpu = readBool(Constants.Remote.KEY_SHOW_GPU, true)
        showCpu = readBool(Constants.Remote.KEY_SHOW_CPU, true)
        showRam = readBool(Constants.Remote.KEY_SHOW_RAM, true)
        showBat = readBool(Constants.Remote.KEY_SHOW_BAT, true)
        showClock = readBool(Constants.Remote.KEY_SHOW_CLOCK, true)
        fontScale = readFloat(Constants.Remote.KEY_FONT_SCALE, 1.0f)
        bgAlpha = readFloat(Constants.Remote.KEY_BG_ALPHA, 0.63f)
        clock24h = readBool(KEY_CLOCK_24H, true)
        appFilterEnabled = readBool(Constants.Modules.KEY_APP_FILTER, false)
        whitelist = readWhitelist()
        updateIntervalMs = readInt(Constants.Modules.KEY_UPDATE_INTERVAL, Constants.Config.UPDATE_INTERVAL_MS)
        wakeIntervalSec = readInt(Constants.Modules.KEY_WAKE_INTERVAL, Constants.Modules.WAKE_INTERVAL_DEFAULT_SEC)
        fpsSourceFlags.clear()
        for (i in 0 until Constants.Modules.FpsSource.COUNT) {
            fpsSourceFlags.add(readBool(Constants.Modules.FpsSource.KEYS[i], true))
        }
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
        /** 12/24 小时制的存储键：沿用旧版 Modules.KEYS 里的键名，读侧 isClock24h 兼容 */
        private const val KEY_CLOCK_24H = "mod_clock_24h"
    }
}
