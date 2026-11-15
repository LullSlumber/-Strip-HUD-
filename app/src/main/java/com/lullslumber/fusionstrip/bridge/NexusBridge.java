package com.lullslumber.fusionstrip.bridge;

import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import com.lullslumber.fusionstrip.constant.Constants;
import com.lullslumber.fusionstrip.utils.LogUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * SystemUI 进程这边：读模块 App 写进来的设置和 GPU 数据。
 *
 * 三条读取通道（v1.6.11）
 * 1. RemotePreferences——libxposed 提供的，最快，但要求模块自己的进程也被框架注入。
 * 2. PrefsSnapshot——经模块 App 的 exported ContentProvider 取本地 SharedPreferences 快照。
 *    不需要注入，但要 SystemUI 能跨应用 query，可能被 SELinux 或包可见性挡住。
 * 3. SettingsChannel——经 Settings.Global。写侧用 root 的 settings put global，
 *    读侧是 system uid 直接读，不经过任何跨应用调用。只要 root 在就通。
 *
 * 为什么要三条：实测澎湃 OS 4 上 LSPosed 只把模块注入了 com.android.systemui，
 * 模块自己的进程没注入。于是 App 侧 getXposedService() 恒为 null，写设置时 Remote
 * 那半段被静默跳过——SystemUI 读 Remote 永远是空的，表现就是所有开关都不生效，
 * 而横条本身还能显示（读不到就用默认值），故障看着像「功能残缺」而不是「通道断了」。
 *
 * 优先级是逐键判断的，不是整体二选一：有些机型几条通道都通，而 Remote 可能只有一部分键。
 */
public final class NexusBridge {

    private static volatile XposedModule module;

    private NexusBridge() {}

    /** Hook 启动时把模块实例绑上，后面读 Remote 要用 */
    public static void bindModule(XposedModule xposedModule) {
        module = xposedModule;
        // 让普通日志（LogUtils.i/w/e）也进 LSPosed 模块日志，不然 SystemUI 这边的
        // 诊断只在 logcat 里，用户导出的日志包看不到
        LogUtils.bindModule(xposedModule);
        try {
            SharedPreferences prefs = xposedModule.getRemotePreferences(Constants.Remote.PREFS_NAME);
            LogUtils.i("NexusBridge bound, remote keys=" + prefs.getAll().size()
                    + " gpu=" + prefs.getInt(Constants.Remote.KEY_GPU_FREQ_MHZ, 0));
        } catch (Throwable t) {
            LogUtils.e("NexusBridge bind prefs failed", t);
        }
    }

    // ---- 分层读取：Remote → provider 快照 → Settings.Global → 默认值，一个个往下试 ----

    private static boolean readBool(String key, boolean def) {
        SharedPreferences p = getPrefs();
        if (p != null && p.contains(key)) {
            return p.getBoolean(key, def);
        }
        if (PrefsSnapshot.contains(key)) {
            return PrefsSnapshot.getBoolean(key, def);
        }
        return SettingsChannel.getBoolean(key, def);
    }

    private static int readInt(String key, int def) {
        SharedPreferences p = getPrefs();
        if (p != null && p.contains(key)) {
            return p.getInt(key, def);
        }
        if (PrefsSnapshot.contains(key)) {
            return PrefsSnapshot.getInt(key, def);
        }
        return SettingsChannel.getInt(key, def);
    }

    private static float readFloat(String key, float def) {
        SharedPreferences p = getPrefs();
        if (p != null && p.contains(key)) {
            return p.getFloat(key, def);
        }
        if (PrefsSnapshot.contains(key)) {
            return PrefsSnapshot.getFloat(key, def);
        }
        return SettingsChannel.getFloat(key, def);
    }

    private static long readLong(String key, long def) {
        SharedPreferences p = getPrefs();
        if (p != null && p.contains(key)) {
            return p.getLong(key, def);
        }
        if (PrefsSnapshot.contains(key)) {
            return PrefsSnapshot.getLong(key, def);
        }
        return SettingsChannel.getLong(key, def);
    }

    private static String readString(String key, String def) {
        SharedPreferences p = getPrefs();
        if (p != null && p.contains(key)) {
            String v = p.getString(key, def);
            return v != null ? v : def;
        }
        if (PrefsSnapshot.contains(key)) {
            return PrefsSnapshot.getString(key, def);
        }
        return SettingsChannel.getString(key, def);
    }

    private static SharedPreferences getPrefs() {
        XposedModule m = module;
        if (m == null) {
            return null;
        }
        try {
            return m.getRemotePreferences(Constants.Remote.PREFS_NAME);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- GPU 数据 ----

    public static int getGpuFreqMhz() {
        int fromPrefs = readInt(Constants.Remote.KEY_GPU_FREQ_MHZ, 0);
        if (fromPrefs > 0) {
            return fromPrefs;
        }
        return readFromRemoteFile();
    }

    public static int getGpuUsage() {
        int pct = readInt(Constants.Remote.KEY_GPU_USAGE_PERCENT, -1);
        return pct >= 0 ? pct : -1;
    }

    public static long getUpdatedAt() {
        return readLong(Constants.Remote.KEY_UPDATED_AT, 0L);
    }

    /** openRemoteFile 备用通道：读 GPU_FREQ_FILE 里的频率值 */
    private static int readFromRemoteFile() {
        XposedModule m = module;
        if (m == null) {
            return 0;
        }
        try (ParcelFileDescriptor pfd = m.openRemoteFile(Constants.Remote.GPU_FREQ_FILE);
             BufferedReader r = new BufferedReader(new InputStreamReader(
                     new ParcelFileDescriptor.AutoCloseInputStream(pfd), StandardCharsets.US_ASCII))) {
            String line = r.readLine();
            if (line == null) {
                return 0;
            }
            return Integer.parseInt(line.trim());
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---- Strip 内容开关（M3）：8 个 chips + 外观；0.4.0 加 FT 与强调线 ----

    public static boolean isStripShowFps() {
        return readBool(Constants.Remote.KEY_SHOW_FPS, true);
    }

    /** 帧时间（FT 8.3ms）独立开关，0.4.0 起从 FPS 拆出 */
    public static boolean isStripShowFt() {
        return readBool(Constants.Remote.KEY_SHOW_FT, true);
    }

    public static boolean isStripShowGraph() {
        return readBool(Constants.Remote.KEY_SHOW_GRAPH, true);
    }

    public static boolean isStripShowGpu() {
        return readBool(Constants.Remote.KEY_SHOW_GPU, true);
    }

    public static boolean isStripShowCpu() {
        return readBool(Constants.Remote.KEY_SHOW_CPU, true);
    }

    public static boolean isStripShowRam() {
        return readBool(Constants.Remote.KEY_SHOW_RAM, true);
    }

    public static boolean isStripShowBat() {
        return readBool(Constants.Remote.KEY_SHOW_BAT, true);
    }

    public static boolean isStripShowClock() {
        return readBool(Constants.Remote.KEY_SHOW_CLOCK, true);
    }

    /** 字号倍率（1.0 = 12sp 基准），钳制到 [0.75, 1.5] 防止存坏值把横条撑爆 */
    public static float getStripFontScale() {
        float v = readFloat(Constants.Remote.KEY_FONT_SCALE, 1.0f);
        if (v < 0.75f) v = 0.75f;
        if (v > 1.5f) v = 1.5f;
        return v;
    }

    /** 背景不透明度，钳制到 [0, 0.95]：全透明看不见条，全不透明太死 */
    public static float getStripBgAlpha() {
        float v = readFloat(Constants.Remote.KEY_BG_ALPHA, 0.63f);
        if (v < 0f) v = 0f;
        if (v > 0.95f) v = 0.95f;
        return v;
    }

    /** 强调线强度（0 = 关闭），钳制到 [0, 1] */
    public static float getStripLineIntensity() {
        float v = readFloat(Constants.Remote.KEY_LINE_INTENSITY, 0.5f);
        if (v < 0f) v = 0f;
        if (v > 1f) v = 1f;
        return v;
    }

    /** 强调线颜色（ARGB），读不到默认琥珀 */
    public static int getStripLineColor() {
        return readInt(Constants.Remote.KEY_LINE_COLOR, 0xFFFFB74D);
    }

    /**
     * 时间用不用 24 小时制。键沿用旧版 Modules.KEYS 里的 "mod_clock_24h"，
     * 读不到默认 true。
     */
    public static boolean isClock24h() {
        return readBool(Constants.Modules.KEYS[Constants.Modules.IDX_CLOCK_24H], true);
    }

    // ---- FPS 采集配置 ----

    /**
     * 读各 FPS 来源的开关，下标跟 Constants.Modules.FpsSource.KEYS 对应。
     *
     * v1.6.4 修的 bug：开关全关掉仍然生效
     * v1.6.2/v1.6.3 在这里先查一个 fps_src_migrated 标记，标记为 false 就一律返回旧版预设。
     * 而 App 写这个标记依赖 XposedService，服务还没绑定时只写进了本地 SharedPreferences——
     * 于是 SystemUI 永远读到 false，用户在界面上的勾选全被忽略，关掉全部来源照样显示 0t。
     * 现在改成逐键读取，每个键的默认值由旧版整数推导。键存在就用键值，不存在才用默认——
     * 迁移不再依赖任何一个额外的写入是否成功。
     */
    public static boolean[] readFpsSourceFlags() {
        int count = Constants.Modules.FpsSource.COUNT;
        boolean[] fallback = legacyPreset();
        boolean[] flags = new boolean[count];
        for (int i = 0; i < count; i++) {
            // 默认值取旧版预设推导出来的那一位：从旧版升级、还没动过开关的用户
            // 保持原有行为；动过的以实际键值为准
            flags[i] = readBool(Constants.Modules.FpsSource.KEYS[i], fallback[i]);
        }
        return flags;
    }

    /**
     * 按旧版的 fps_source 整数取对应预设，当成逐键读取的默认值。
     * 读不到就返回「全部」，等价于旧版的「自动」。
     */
    private static boolean[] legacyPreset() {
        int legacy = readInt(Constants.Modules.KEY_FPS_SOURCE,
                Constants.Modules.FPS_SOURCE_AUTO);
        int preset = (legacy >= 0 && legacy < Constants.Modules.FpsSource.PRESETS.length)
                ? legacy : 0;
        return Constants.Modules.FpsSource.PRESETS[preset].clone();
    }

    /**
     * 「SurfaceFlinger 走 root」开关；默认关。
     *
     * 关着的时候走 IBinder.dump()，Binder 调用不会创建进程；开了之后每次取数多一次
     * dumpsys 的 fork+exec，只有 Binder 通道被 SELinux 拦住的时候才需要。
     */
    public static boolean isSfPreferRoot() {
        return readBool(Constants.Modules.KEY_SF_PREFER_ROOT, false);
    }

    /** 「FPS 诊断」开关；默认关 */
    public static boolean isFpsDebugEnabled() {
        return readBool(Constants.Modules.KEY_FPS_DEBUG, false);
    }

    /**
     * 双电芯机型把两块电池的电流电压分开读再合并功率（采集端配置，与 UI 无关）。
     */
    public static boolean isDualCellEnabled() {
        return readBool(Constants.Modules.KEY_DUAL_CELL, false);
    }

    // ---- 采集周期 / 唤醒 ----

    /** 采集和 UI 刷新周期（毫秒） */
    public static int getUpdateIntervalMs() {
        int ms = readInt(Constants.Modules.KEY_UPDATE_INTERVAL,
                Constants.Config.UPDATE_INTERVAL_MS);
        if (ms < 500) ms = 500;
        if (ms > 3000) ms = 3000;
        return ms;
    }

    /** 模块进程后台唤醒间隔（秒），0 = 只用心跳 */
    public static int getWakeIntervalSec() {
        return readInt(Constants.Modules.KEY_WAKE_INTERVAL,
                Constants.Modules.WAKE_INTERVAL_DEFAULT_SEC);
    }

    // ---- 显示时机（唯一逻辑：应用过滤） ----

    /** 「仅选定应用显示」开关；默认关 = 所有界面显示 */
    public static boolean isAppFilterEnabled() {
        return readBool(Constants.Modules.KEY_APP_FILTER, false);
    }

    /**
     * 应用白名单。包名用换行分隔存成一个字符串，读侧按空格和换行两种分隔符都切：
     * Settings.Global 通道会把值里的换行替换成空格（见 SettingsChannel.encode）。
     */
    public static Set<String> getWhitelist() {
        String raw = readString(Constants.Modules.KEY_APP_WHITELIST, "");
        if (raw == null || raw.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> out = new HashSet<>();
        for (String pkg : raw.split("[\\n ]")) {
            pkg = pkg.trim();
            if (!pkg.isEmpty()) {
                out.add(pkg);
            }
        }
        return out;
    }
}
