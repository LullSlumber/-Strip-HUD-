package com.lullslumber.fusionstrip.ui

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lullslumber.fusionstrip.App
import com.lullslumber.fusionstrip.bridge.SettingsChannel
import com.lullslumber.fusionstrip.constant.Constants
import com.lullslumber.fusionstrip.ui.theme.CardStroke
import com.lullslumber.fusionstrip.ui.theme.FusionStripTheme
import com.lullslumber.fusionstrip.ui.theme.MdThemeOnSurface
import com.lullslumber.fusionstrip.ui.theme.MdThemeOnSurfaceVariant
import com.lullslumber.fusionstrip.ui.theme.MdThemePrimary
import com.lullslumber.fusionstrip.utils.LogUtils
import com.lullslumber.fusionstrip.utils.RootShell
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
    private var showFt by mutableStateOf(true)
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
    /** 强调线强度 0~1，0 = 关闭 */
    private var lineIntensity by mutableStateOf(0.5f)
    /** 强调线颜色（ARGB int 存储页面用 Color 表示） */
    private var lineColor by mutableStateOf(Color(0xFFFFB74D))

    // ---- 界面主题（App 自身，与横条配置无关，存本地 prefs） ----
    private var themeMode by mutableStateOf(THEME_FOLLOW_SYSTEM)
    private var dynamicColor by mutableStateOf(true)

    // ---- 显示时机（唯一逻辑：应用过滤） ----
    private var appFilterEnabled by mutableStateOf(false)
    private var whitelist by mutableStateOf<Set<String>>(emptySet())
    private var showAppPicker by mutableStateOf(false)

    // ---- 数据 ----
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
            val dark = when (themeMode) {
                THEME_LIGHT -> false
                THEME_DARK -> true
                else -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            FusionStripTheme(darkTheme = dark, dynamic = dynamicColor) {
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
                OutlinedButton(
                    onClick = { restartSystemUi() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("重启 SystemUI")
                }
                Text(
                    "横条没出现或改了注入相关设置时用。需要 root，重启约需 5~10 秒。",
                    fontSize = 12.sp,
                    color = MdThemeOnSurfaceVariant
                )
            }
        }
    }

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
                SwitchRow("FPS", showFps) { showFps = it; saveSwitch(Constants.Remote.KEY_SHOW_FPS, it) }
                SwitchRow("帧时间", showFt, subtitle = "FT 8.3ms，由 FPS 换算") {
                    showFt = it; saveSwitch(Constants.Remote.KEY_SHOW_FT, it)
                }
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
                // 强调线：横条贴游戏一侧的装饰细线（移植自 DroidDeck accent line）
                SliderRow(
                    label = "强调线粗细",
                    valueText = if (lineIntensity <= 0.001f) "关闭"
                        else String.format(Locale.US, "%.0f%%", lineIntensity * 100f),
                    value = lineIntensity,
                    onValueChange = { lineIntensity = (it * 20f).toInt() / 20f },
                    onValueChangeFinished = { saveFloat(Constants.Remote.KEY_LINE_INTENSITY, lineIntensity) },
                    range = 0f..1f
                )
                if (lineIntensity > 0.001f) {
                    ColorDotRow(
                        selected = lineColor,
                        onSelect = {
                            lineColor = it
                            saveInt(Constants.Remote.KEY_LINE_COLOR, it.toArgb())
                        }
                    )
                }
                SwitchRow("24 小时制", clock24h) { clock24h = it; saveSwitch(KEY_CLOCK_24H, it) }
                HorizontalDivider()
                // 以下两项只影响这个设置页自己（存本地 prefs），与横条无关
                Text("界面主题", fontSize = 15.sp, color = MdThemeOnSurface)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = themeMode == THEME_FOLLOW_SYSTEM,
                        onClick = { themeMode = THEME_FOLLOW_SYSTEM; saveUiPrefs() },
                        label = { Text("跟随系统") }
                    )
                    FilterChip(
                        selected = themeMode == THEME_LIGHT,
                        onClick = { themeMode = THEME_LIGHT; saveUiPrefs() },
                        label = { Text("白天") }
                    )
                    FilterChip(
                        selected = themeMode == THEME_DARK,
                        onClick = { themeMode = THEME_DARK; saveUiPrefs() },
                        label = { Text("深色") }
                    )
                }
                SwitchRow(
                    "动态取色",
                    dynamicColor,
                    subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                        "Material You：跟随系统壁纸主题色" else "需要 Android 12+"
                ) {
                    dynamicColor = it
                    saveUiPrefs()
                }
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

                // FPS 数据源固定高通（0.4.0 精简）：面板 measured_fps 优先，TimeStats 兜底。
                // 联发科/GED 等采集分支保留在代码里，但不再暴露开关。
                statusRow("FPS 数据源", "高通（面板 + TimeStats）")

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

    /** 强调线颜色色板：一行色点，选中的描白圈 */
    @Composable
    private fun ColorDotRow(selected: Color, onSelect: (Color) -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            LINE_COLORS.forEach { c ->
                val isSel = selected == c
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .background(color = c, shape = CircleShape)
                        .border(
                            width = if (isSel) 3.dp else 1.dp,
                            color = if (isSel) MdThemeOnSurface else CardStroke,
                            shape = CircleShape
                        )
                        .clickable { onSelect(c) }
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
        showFt = readBool(Constants.Remote.KEY_SHOW_FT, true)
        showGraph = readBool(Constants.Remote.KEY_SHOW_GRAPH, true)
        showLow1 = readBool(Constants.Remote.KEY_SHOW_LOW1, true)
        showGpu = readBool(Constants.Remote.KEY_SHOW_GPU, true)
        showCpu = readBool(Constants.Remote.KEY_SHOW_CPU, true)
        showRam = readBool(Constants.Remote.KEY_SHOW_RAM, true)
        showBat = readBool(Constants.Remote.KEY_SHOW_BAT, true)
        showClock = readBool(Constants.Remote.KEY_SHOW_CLOCK, true)
        fontScale = readFloat(Constants.Remote.KEY_FONT_SCALE, 1.0f)
        bgAlpha = readFloat(Constants.Remote.KEY_BG_ALPHA, 0.63f)
        lineIntensity = readFloat(Constants.Remote.KEY_LINE_INTENSITY, 0.5f)
        lineColor = Color(readInt(Constants.Remote.KEY_LINE_COLOR, 0xFFFFB74D.toInt()))
        clock24h = readBool(KEY_CLOCK_24H, true)
        appFilterEnabled = readBool(Constants.Modules.KEY_APP_FILTER, false)
        whitelist = readWhitelist()
        updateIntervalMs = readInt(Constants.Modules.KEY_UPDATE_INTERVAL, Constants.Config.UPDATE_INTERVAL_MS)
        wakeIntervalSec = readInt(Constants.Modules.KEY_WAKE_INTERVAL, Constants.Modules.WAKE_INTERVAL_DEFAULT_SEC)
        // FPS 数据源固定高通（0.4.0）：每次启动都把来源开关刷成高通预设，
        // 自愈老版本残留的「全部/联发科」组合。采集代码里的其他来源分支保留不删。
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
        val p = getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
        themeMode = p.getInt(KEY_UI_THEME_MODE, THEME_FOLLOW_SYSTEM)
        dynamicColor = p.getBoolean(KEY_UI_DYNAMIC_COLOR, true)
    }

    private fun saveUiPrefs() {
        getSharedPreferences(UI_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_UI_THEME_MODE, themeMode)
            .putBoolean(KEY_UI_DYNAMIC_COLOR, dynamicColor)
            .apply()
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
        /** 12/24 小时制的存储键：沿用旧版 Modules.KEYS 里的键名，读侧 isClock24h 兼容 */
        private const val KEY_CLOCK_24H = "mod_clock_24h"

        // ---- 界面主题（App 自身，本地 prefs，不进横条配置管道） ----
        private const val UI_PREFS_NAME = "ui"
        private const val KEY_UI_THEME_MODE = "theme_mode"
        private const val KEY_UI_DYNAMIC_COLOR = "dynamic_color"
        private const val THEME_FOLLOW_SYSTEM = 0
        private const val THEME_LIGHT = 1
        private const val THEME_DARK = 2

        /** Constants.Modules.FpsSource.PRESETS 里高通预设的下标 */
        private const val FPS_PRESET_QUALCOMM = 1

        /** 强调线色板：琥珀 / 白 / 红 / 橙 / 黄 / 绿 / 蓝 / 紫 */
        private val LINE_COLORS = listOf(
            Color(0xFFFFB74D),
            Color(0xFFFFFFFF),
            Color(0xFFFF5252),
            Color(0xFFFF9800),
            Color(0xFFFFEB3B),
            Color(0xFF66BB6A),
            Color(0xFF4FC3F7),
            Color(0xFFAB47BC)
        )
    }
}
