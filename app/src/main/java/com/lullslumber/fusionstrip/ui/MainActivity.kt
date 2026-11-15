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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lullslumber.fusionstrip.App
import com.lullslumber.fusionstrip.bridge.SettingsChannel
import com.lullslumber.fusionstrip.constant.Constants
import com.lullslumber.fusionstrip.ui.theme.CardStroke
import com.lullslumber.fusionstrip.ui.theme.CardSurface
import com.lullslumber.fusionstrip.ui.theme.FusionStripTheme
import com.lullslumber.fusionstrip.ui.theme.MdThemeOnPrimaryContainer
import com.lullslumber.fusionstrip.ui.theme.MdThemeOnSurface
import com.lullslumber.fusionstrip.ui.theme.MdThemeOnSurfaceVariant
import com.lullslumber.fusionstrip.ui.theme.MdThemePrimary
import com.lullslumber.fusionstrip.ui.theme.MdThemePrimaryContainer
import com.lullslumber.fusionstrip.ui.theme.MdThemeSurface
import com.lullslumber.fusionstrip.ui.theme.StatusConnected
import com.lullslumber.fusionstrip.ui.theme.StatusConnectedBg
import com.lullslumber.fusionstrip.ui.theme.StatusDisconnected
import com.lullslumber.fusionstrip.ui.theme.StatusDisconnectedBg
import com.lullslumber.fusionstrip.ui.theme.ToggleOffContainer
import com.lullslumber.fusionstrip.ui.theme.ToggleOnBorder
import com.lullslumber.fusionstrip.ui.theme.ToggleOnContainer
import com.lullslumber.fusionstrip.utils.LogUtils
import com.lullslumber.fusionstrip.utils.RootShell
import io.github.libxposed.service.XposedService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * FusionStrip 设置页。
 *
 * 界面沿用 NexusFloat 的组件语言（HeaderCard / SectionCard / ToggleRow /
 * StepperRow / MetricTile / SegmentedSelector / ActionRow），内容只保留
 * Strip 横条需要的设置：内容开关、外观、应用过滤、数据采集、维护。
 * NexusFloat 的统计页、帧记录、壁纸、监视条模块开关一律不搬。
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
    private var versionName by mutableStateOf("")

    private val statusPoll = object : Runnable {
        override fun run() {
            refreshStatus()
            mainHandler.postDelayed(this, 2000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (t: Throwable) {
            ""
        }
        loadSettings()
        setContent {
            val dark = when (themeMode) {
                THEME_LIGHT -> false
                THEME_DARK -> true
                else -> isSystemInDarkTheme()
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

    // ---------------- UI（NexusFloat 组件语言） ----------------

    @Composable
    private fun mainScreen() {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MdThemeSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                headerCard()
                statusCard()
                SectionCard(title = "显示项目") {
                    ToggleRow("FPS", showFps) { showFps = it; saveSwitch(Constants.Remote.KEY_SHOW_FPS, it) }
                    Spacer(Modifier.height(6.dp))
                    ToggleRow("帧时间", showFt, description = "FT 8.3ms，由 FPS 换算") {
                        showFt = it; saveSwitch(Constants.Remote.KEY_SHOW_FT, it)
                    }
                    Spacer(Modifier.height(6.dp))
                    ToggleRow("帧率历史图", showGraph) { showGraph = it; saveSwitch(Constants.Remote.KEY_SHOW_GRAPH, it) }
                    Spacer(Modifier.height(6.dp))
                    ToggleRow("1% low", showLow1) { showLow1 = it; saveSwitch(Constants.Remote.KEY_SHOW_LOW1, it) }
                    Spacer(Modifier.height(6.dp))
                    ToggleRow("GPU 占用", showGpu) { showGpu = it; saveSwitch(Constants.Remote.KEY_SHOW_GPU, it) }
                    Spacer(Modifier.height(6.dp))
                    ToggleRow("CPU 占用", showCpu) { showCpu = it; saveSwitch(Constants.Remote.KEY_SHOW_CPU, it) }
                    Spacer(Modifier.height(6.dp))
                    ToggleRow("内存占用", showRam) { showRam = it; saveSwitch(Constants.Remote.KEY_SHOW_RAM, it) }
                    Spacer(Modifier.height(6.dp))
                    ToggleRow("电池", showBat, description = "温度 / 功率 / 剩余续航") {
                        showBat = it; saveSwitch(Constants.Remote.KEY_SHOW_BAT, it)
                    }
                    Spacer(Modifier.height(6.dp))
                    ToggleRow("时钟", showClock) { showClock = it; saveSwitch(Constants.Remote.KEY_SHOW_CLOCK, it) }
                }
                SectionCard(title = "外观") {
                    StepperRow(
                        label = "字号倍率",
                        value = String.format(Locale.US, "%.2f×", fontScale),
                        description = "横条整体字号缩放",
                        onDecrease = {
                            fontScale = (fontScale - 0.05f).coerceIn(0.75f, 1.5f)
                            saveFloat(Constants.Remote.KEY_FONT_SCALE, fontScale)
                        },
                        onIncrease = {
                            fontScale = (fontScale + 0.05f).coerceIn(0.75f, 1.5f)
                            saveFloat(Constants.Remote.KEY_FONT_SCALE, fontScale)
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    StepperRow(
                        label = "背景不透明度",
                        value = String.format(Locale.US, "%.0f%%", bgAlpha * 100f),
                        description = "0% 全透明，100% 纯黑",
                        onDecrease = {
                            bgAlpha = (bgAlpha - 0.05f).coerceIn(0f, 0.95f)
                            saveFloat(Constants.Remote.KEY_BG_ALPHA, bgAlpha)
                        },
                        onIncrease = {
                            bgAlpha = (bgAlpha + 0.05f).coerceIn(0f, 0.95f)
                            saveFloat(Constants.Remote.KEY_BG_ALPHA, bgAlpha)
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    StepperRow(
                        label = "强调线粗细",
                        value = if (lineIntensity <= 0.001f) "关闭"
                            else String.format(Locale.US, "%.0f%%", lineIntensity * 100f),
                        description = "横条贴游戏一侧的装饰细线",
                        onDecrease = {
                            lineIntensity = ((lineIntensity - 0.05f) * 20f).toInt() / 20f
                            if (lineIntensity < 0f) lineIntensity = 0f
                            saveFloat(Constants.Remote.KEY_LINE_INTENSITY, lineIntensity)
                        },
                        onIncrease = {
                            lineIntensity = ((lineIntensity + 0.05f) * 20f).toInt() / 20f
                            if (lineIntensity > 1f) lineIntensity = 1f
                            saveFloat(Constants.Remote.KEY_LINE_INTENSITY, lineIntensity)
                        }
                    )
                    if (lineIntensity > 0.001f) {
                        Spacer(Modifier.height(8.dp))
                        LineColorRow(
                            selected = lineColor,
                            onSelect = {
                                lineColor = it
                                saveInt(Constants.Remote.KEY_LINE_COLOR, it.toArgb())
                            }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    ToggleRow("24 小时制", clock24h) { clock24h = it; saveSwitch(KEY_CLOCK_24H, it) }
                    Spacer(Modifier.height(8.dp))
                    ToggleRow(
                        "动态取色",
                        dynamicColor,
                        description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                            "Material You：跟随系统壁纸主题色（仅本界面）" else "需要 Android 12+",
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ) {
                        dynamicColor = it
                        saveUiPrefs()
                    }
                }
                SectionCard(title = "显示") {
                    ToggleRow(
                        "仅选定应用显示",
                        appFilterEnabled,
                        description = if (appFilterEnabled)
                            "已选 ${whitelist.size} 个应用" +
                                if (whitelist.isEmpty()) "（未选任何应用时横条不显示）" else ""
                            else "关：所有界面显示"
                    ) {
                        appFilterEnabled = it
                        saveSwitch(Constants.Modules.KEY_APP_FILTER, it)
                    }
                    if (appFilterEnabled) {
                        Spacer(Modifier.height(8.dp))
                        ActionRow(
                            label = "选择应用",
                            description = "勾选哪些应用里显示横条",
                            buttonText = if (whitelist.isEmpty()) "去选择" else "编辑"
                        ) { showAppPicker = true }
                    }
                }
                SectionCard(title = "数据") {
                    // FPS 数据源固定高通（0.4.0 精简）：面板 measured_fps 优先，TimeStats 兜底
                    ToggleRow("FPS 数据源：高通", true, enabled = false,
                        description = "面板 measured_fps 优先，TimeStats 兜底") { }
                    Spacer(Modifier.height(8.dp))
                    StepperRow(
                        label = "刷新周期",
                        value = "${updateIntervalMs} ms",
                        description = "采集一轮数据的间隔",
                        onDecrease = {
                            updateIntervalMs = (updateIntervalMs - 100).coerceIn(500, 3000)
                            saveInt(Constants.Modules.KEY_UPDATE_INTERVAL, updateIntervalMs)
                        },
                        onIncrease = {
                            updateIntervalMs = (updateIntervalMs + 100).coerceIn(500, 3000)
                            saveInt(Constants.Modules.KEY_UPDATE_INTERVAL, updateIntervalMs)
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    StepperRow(
                        label = "后台唤醒间隔",
                        value = if (wakeIntervalSec <= 0) "关闭" else "${wakeIntervalSec}s",
                        description = "模块进程被清后定时拉回，0 = 关闭",
                        onDecrease = {
                            wakeIntervalSec = (wakeIntervalSec - 5).coerceAtLeast(0)
                            saveInt(Constants.Modules.KEY_WAKE_INTERVAL, wakeIntervalSec)
                        },
                        onIncrease = {
                            wakeIntervalSec = (wakeIntervalSec + 5)
                                .coerceAtMost(Constants.Modules.WAKE_INTERVAL_MAX_SEC)
                            saveInt(Constants.Modules.KEY_WAKE_INTERVAL, wakeIntervalSec)
                        }
                    )
                }
                SectionCard(title = "维护") {
                    ActionRow(
                        label = "重启 SystemUI",
                        description = "横条没出现或改了注入相关设置时用，需 root",
                        buttonText = "重启"
                    ) { restartSystemUi() }
                }
                Text(
                    "改动约 1 秒内生效，无需重启。",
                    fontSize = 12.sp,
                    color = MdThemeOnSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    /** 标题卡：NexusFloat HeaderCard 同款（primaryContainer 底 + 版本徽标 + 主题选择器） */
    @Composable
    private fun headerCard() {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MdThemePrimaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "FusionStrip",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MdThemeOnPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Strip 性能横条 · LSPosed 模块",
                            fontSize = 13.sp,
                            color = MdThemeOnSurfaceVariant
                        )
                        Text(
                            text = "UI 致敬 NexusFloat（酷安@叶落雨巷）",
                            fontSize = 13.sp,
                            color = MdThemeOnSurfaceVariant
                        )
                    }
                    if (versionName.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(MdThemeSurface)
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "v$versionName",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MdThemePrimary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
                // 主题切换只影响 App 界面本身，跟横条无关，搁标题卡里最合适
                SegmentedSelector(
                    options = listOf("跟随系统", "白天", "深色"),
                    selectedIndex = themeMode,
                    onSelect = { themeMode = it; saveUiPrefs() }
                )
            }
        }
    }

    /** 运行状态卡：LSPosed 徽标 + GPU 读数格子 + 最近更新时间 */
    @Composable
    private fun statusCard() {
        SectionCard(title = "运行状态") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LSPosed 服务",
                    fontSize = 14.sp,
                    color = MdThemeOnSurface,
                    modifier = Modifier.weight(1f)
                )
                val badgeBg = if (xposedConnected) StatusConnectedBg else StatusDisconnectedBg
                val badgeTextColor = if (xposedConnected) StatusConnected else StatusDisconnected
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeBg)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (xposedConnected) "已连接" else "未连接",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeTextColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricTile(
                    title = "GPU 频率",
                    value = gpuFreqText,
                    modifier = Modifier.weight(1f)
                )
                MetricTile(
                    title = "GPU 占用",
                    value = gpuUsageText,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "数据更新：$lastUpdateText",
                fontSize = 11.sp,
                color = MdThemeOnSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }

    /** 一块读数：小标题 + 大字数值（NexusFloat MetricTile 同款，去掉图标层） */
    @Composable
    private fun MetricTile(
        title: String,
        value: String,
        modifier: Modifier = Modifier
    ) {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(ToggleOffContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                color = MdThemeOnSurfaceVariant,
                maxLines = 2,
                lineHeight = 13.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = MdThemeOnSurface,
                maxLines = 1,
                softWrap = false
            )
        }
    }

    /**
     * 卡片外壳：统一圆角、描边和内边距，再带个小标题（NexusFloat SectionCard 同款）。
     * 卡片背景 50% 透明，让底色透出来。
     */
    @Composable
    private fun SectionCard(
        title: String,
        content: @Composable ColumnScope.() -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = CardSurface.copy(alpha = 0.5f)
            ),
            border = BorderStroke(1.dp, CardStroke),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MdThemePrimary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                )
                content()
            }
        }
    }

    /**
     * 一体化开关行（NexusFloat ToggleRow 同款）：整行是一个圆角容器，名称和开关都在里头。
     * 开和关的区别体现在容器底色、描边颜色、右侧「开/关」徽标和 Switch 配色四处。
     */
    @Composable
    private fun ToggleRow(
        label: String,
        checked: Boolean,
        enabled: Boolean = true,
        description: String? = null,
        onCheckedChange: (Boolean) -> Unit
    ) {
        val on = checked && enabled
        val container = if (on) ToggleOnContainer else ToggleOffContainer
        val borderColor = if (on) ToggleOnBorder else CardStroke
        val labelColor = when {
            !enabled -> MdThemeOnSurface.copy(alpha = 0.38f)
            on -> MdThemeOnSurface
            else -> MdThemeOnSurfaceVariant
        }
        val shape = RoundedCornerShape(14.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(container)
                .border(1.dp, borderColor, shape)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange
                )
                .padding(horizontal = 14.dp, vertical = if (description == null) 12.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = labelColor
                )
                if (description != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        fontSize = 11.sp,
                        color = MdThemeOnSurfaceVariant.copy(alpha = if (enabled) 1f else 0.5f)
                    )
                }
            }
            Text(
                text = if (checked) "开" else "关",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (on) MdThemePrimary else MdThemeOnSurfaceVariant.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = checked,
                // 点击由整行接管，这里传 null 让 Switch 变成纯指示
                onCheckedChange = null,
                enabled = enabled
            )
        }
    }

    /** 一行「名称 + 数值 + 加减按钮」，容器样式跟 ToggleRow 一致（NexusFloat StepperRow 同款） */
    @Composable
    private fun StepperRow(
        label: String,
        value: String,
        onDecrease: () -> Unit,
        onIncrease: () -> Unit,
        description: String = ""
    ) {
        val shape = RoundedCornerShape(14.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(ToggleOffContainer)
                .border(1.dp, CardStroke, shape)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MdThemeOnSurface
                )
                if (description.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = description,
                        fontSize = 11.sp,
                        color = MdThemeOnSurfaceVariant
                    )
                }
            }
            Text(
                text = value,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MdThemePrimary
            )
            Spacer(modifier = Modifier.width(10.dp))
            FilledTonalButton(
                onClick = onDecrease,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(34.dp)
            ) {
                Text(text = "−", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onIncrease,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(34.dp)
            ) {
                Text(text = "+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    /** 一行「名称 + 说明 + 操作按钮」（NexusFloat ActionRow 同款） */
    @Composable
    private fun ActionRow(
        label: String,
        description: String,
        buttonText: String,
        onClick: () -> Unit
    ) {
        val shape = RoundedCornerShape(14.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(ToggleOffContainer)
                .border(1.dp, CardStroke, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MdThemeOnSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = MdThemeOnSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            FilledTonalButton(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Text(text = buttonText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    /** 分段选择器（NexusFloat SegmentedSelector 同款） */
    @Composable
    private fun SegmentedSelector(
        options: List<String>,
        selectedIndex: Int,
        onSelect: (Int) -> Unit
    ) {
        val shape = RoundedCornerShape(14.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(ToggleOffContainer)
                .border(1.dp, CardStroke, shape)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                val itemShape = RoundedCornerShape(11.dp)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(itemShape)
                        .background(if (selected) MdThemePrimaryContainer else CardSurface)
                        .border(
                            1.dp,
                            if (selected) ToggleOnBorder else CardStroke,
                            itemShape
                        )
                        .toggleable(
                            value = selected,
                            role = Role.RadioButton,
                            onValueChange = { if (it) onSelect(index) }
                        )
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MdThemeOnPrimaryContainer else MdThemeOnSurfaceVariant
                    )
                }
            }
        }
    }

    /** 强调线颜色色板（NexusFloat ColorPickerRow 同款容器）：一行圆角色块，选中描主题色边 */
    @Composable
    private fun LineColorRow(
        selected: Color,
        onSelect: (Color) -> Unit
    ) {
        val shape = RoundedCornerShape(14.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(ToggleOffContainer)
                .border(1.dp, CardStroke, shape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "强调线颜色",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MdThemeOnSurface,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = LINE_COLOR_NAMES.getOrElse(LINE_COLORS.indexOf(selected)) { "" },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MdThemePrimary
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LINE_COLORS.forEachIndexed { index, c ->
                    val isSel = selected == c
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(c)
                            .border(
                                width = if (isSel) 2.dp else 1.dp,
                                color = if (isSel) MdThemePrimary else CardStroke,
                                shape = RoundedCornerShape(9.dp)
                            )
                            .toggleable(
                                value = isSel,
                                role = Role.RadioButton,
                                onValueChange = { if (it) onSelect(c) }
                            )
                    )
                }
            }
        }
    }

    // ---------------- 数据 / 持久化 ----------------

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

        private val LINE_COLOR_NAMES = listOf(
            "琥珀", "白", "红", "橙", "黄", "绿", "蓝", "紫"
        )
    }
}
