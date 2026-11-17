package com.lullslumber.fusionstrip.ui;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.WindowManager;

import com.lullslumber.fusionstrip.bridge.NexusBridge;
import com.lullslumber.fusionstrip.bridge.PrefsSnapshot;
import com.lullslumber.fusionstrip.bridge.SettingsChannel;
import com.lullslumber.fusionstrip.collector.ForegroundAppTracker;
import com.lullslumber.fusionstrip.constant.Constants;
import com.lullslumber.fusionstrip.service.CollectorSignal;
import com.lullslumber.fusionstrip.ui.view.StripView;
import com.lullslumber.fusionstrip.utils.LogUtils;

import java.util.Set;

/**
 * 用独立悬浮窗装 StripView，按应用过滤决定要不要显示（M3 简化版）。
 *
 * 显示逻辑只剩一条：「仅选定应用显示」开关 + 白名单。开关关着恒显示，
 * 开着只显示白名单里的应用。息屏/横竖屏/充电判定全部移除——独立悬浮窗
 * 不受状态栏和方向影响，游戏横条本来就该到处显示。
 *
 * 配置变更广播触发一次立即判定，另有 Constants.Overlay#WATCH_INTERVAL_MS
 * 的周期轮询，用来抓 App 端刚改的开关和前台应用切换；每轮顺带把 Strip
 * 配置（chips 开关/字号/透明度/时钟制式）下发给 StripView 热更。
 *
 * 隐藏的时候不只是看不见：StripView 的采集线程会随 detach 停掉，模块进程
 * 里的 root GPU 采集也会被通知暂停，所以隐藏状态下不耗电。
 *
 * 这段代码跑在 SystemUI 进程（system uid）里，已经持有添加系统窗口需要的
 * 权限，模块自己的 Manifest 不用声明 SYSTEM_ALERT_WINDOW。
 */
public final class MonitorOverlay {

    private final Context context;
    private final WindowManager windowManager;
    /** 主线程 handler：窗口增删、View 操作只允许在这条线程做 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /**
     * 后台轮询线程（v0.4.0 死机修复）。
     *
     * 每秒一轮的设置读取全是跨进程调用：Settings.Global 走 provider、
     * RemotePreferences 走模块服务、getRunningTasks 走 ATMS。原先这些都在
     * SystemUI 主线程上做——系统繁忙（应用启动、屏幕旋转、解锁）时每一次
     * 调用都可能被堵几百毫秒，1Hz 的节奏把主线程持续占满，症状就是
     * 进不去应用/游戏、锁屏界面无响应只能强重启。
     * 现在读取在专用线程做，主线程只收结果（applyConfig + 窗口增删）。
     */
    private Handler bgHandler;
    /** 熄屏判定：熄屏期间整条轮询链路停摆 */
    private final PowerManager powerManager;
    /** 锁屏判定：锁屏界面不显示横条，也不做任何读取 */
    private final KeyguardManager keyguardManager;
    /** 前台应用判定，只有开了「仅选定应用显示」才会用到它 */
    private final ForegroundAppTracker foregroundTracker;

    private StripView stripView;
    private WindowManager.LayoutParams params;
    private BroadcastReceiver configReceiver;

    /**
     * 窗口状态机的瞬态。
     *
     * IDLE      稳态，不在添加也不在移除
     * ADDING    addToWindow() 执行中
     * REMOVING  removeFromWindow() 执行中
     *
     * 只有 IDLE 才允许 evaluate() 触发新的 add / remove。add 和 remove 都是异步的，
     * 布尔标志无法表达"正在进行中"这个中间态——PiP 切换时 evaluate() 会被轮询和
     * ACTION_CONFIGURATION_CHANGED 广播两条路径同时驱动，两边都读到"未添加"就各自
     * 走一次 addView，SurfaceFlinger 里留下两个 token，合成后就是重影。
     */
    private static final int STATE_IDLE = 0;
    private static final int STATE_ADDING = 1;
    private static final int STATE_REMOVING = 2;
    private int windowState = STATE_IDLE;

    /**
     * 窗口当前是否真实存在（稳态）。
     *
     * 跟 windowState 分开是有意的：后者的 IDLE 只表示"没有正在进行的操作"，
     * 不代表窗口一定在。isShowing() 和配置同步要的是"在不在"，
     * evaluate() 的并发保护要的是"能不能动手"，混用一个变量会让 isShowing()
     * 在窗口从未添加成功时也返回 true。
     */
    private boolean attached;
    /** watch 循环在不在跑 */
    private boolean watching;
    /** 上次已经通知给模块进程的采集状态，免得每秒重复下发同一条指令 */
    private Boolean lastCollectSignal;
    /** 同一个状态连续保持了几轮，用来做周期性复发指令 */
    private int signalTicks;
    /** 距上次后台唤醒过了多少轮（v1.8.11） */
    private int wakeElapsedTicks;

    private final Runnable watchRunnable = new Runnable() {
        @Override
        public void run() {
            if (!watching) {
                return;
            }
            try {
                evaluate();
            } catch (Throwable t) {
                // 后台线程里的任何意外都不能带走轮询循环，
                // 否则横条的配置同步和显示判定就永远停了
                LogUtils.w("MonitorOverlay evaluate crashed", t);
            }
            bgHandler.postDelayed(this, Constants.Overlay.WATCH_INTERVAL_MS);
        }
    };

    public MonitorOverlay(Context context) {
        this.context = context;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        this.keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        this.foregroundTracker = new ForegroundAppTracker(context);
        // 设置快照要拿 Context 去 query 模块 App 的 provider
        PrefsSnapshot.bind(context);
    }

    /**
     * 用独立悬浮窗托管 Strip。按 Constants.Overlay#WINDOW_TYPES 的顺序试窗口
     * 类型，有一个成功就算可用；全失败返回 false，调用方留给兜底路径重试。
     */
    public boolean start(StripView view) {
        if (windowManager == null || view == null) {
            return false;
        }
        stop();
        stripView = view;
        if (!probeWindowType()) {
            stripView = null;
            return false;
        }
        startWatching();
        return true;
    }

    /** 停止托管：移除悬浮窗，停掉 watch 循环和广播 */
    public void stop() {
        watching = false;
        if (bgHandler != null) {
            bgHandler.removeCallbacks(watchRunnable);
        }
        unregisterConfigReceiver();
        removeFromWindow();
        stripView = null;
        params = null;
        // 把去重记录清掉：下次 evaluate 必定重新下发一次采集状态。
        // 这里不主动发 pause——stop() 多数时候是重新注入前的清理，
        // 发了马上又得 resume，白折腾一轮跨进程调用
        lastCollectSignal = null;
        signalTicks = 0;
    }

    public boolean isShowing() {
        return attached;
    }

    /**
     * 试出一个能用的窗口类型：真的 addView 一次，成了就留着
     * （后面由 evaluate() 按开关决定去留）。
     */
    private boolean probeWindowType() {
        for (int type : Constants.Overlay.WINDOW_TYPES) {
            params = buildParams(type);
            // View 已有 parent 说明窗口其实已经在，别再 add 一次
            if (stripView.getParent() != null) {
                attached = true;
                windowState = STATE_IDLE;
                LogUtils.i("MonitorOverlay already attached, skip add for type " + type);
                return true;
            }
            windowState = STATE_ADDING;
            try {
                windowManager.addView(stripView, params);
                attached = true;
                windowState = STATE_IDLE;
                LogUtils.i("MonitorOverlay added with window type " + type);
                return true;
            } catch (Throwable t) {
                // 把异常类名和消息带上：BadTokenException 说明窗口类型不被放行，
                // SecurityException 说明缺权限，这俩的对策完全不一样
                windowState = STATE_IDLE;
                LogUtils.w("MonitorOverlay addView failed for type " + type
                        + ": " + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
            }
        }
        LogUtils.e("MonitorOverlay: all " + Constants.Overlay.WINDOW_TYPES.length
                + " window types rejected");
        params = null;
        return false;
    }

    private void startWatching() {
        ensureBgHandler();
        registerConfigReceiver();
        watching = true;
        bgHandler.removeCallbacks(watchRunnable);
        bgHandler.post(watchRunnable);
    }

    /** 惰性建后台轮询线程；SystemUI 进程存活期内复用，不随 stop() 退出 */
    private void ensureBgHandler() {
        if (bgHandler != null) {
            return;
        }
        HandlerThread thread = new HandlerThread("fs-strip-watch");
        thread.start();
        bgHandler = new Handler(thread.getLooper());
    }

    /**
     * 轮询主体，跑在后台线程（v0.4.0 死机修复）。
     *
     * 原版整个 evaluate 跑在 SystemUI 主线程上，每秒一次的设置读取
     * （Settings provider、RemotePreferences、getRunningTasks）全是跨进程
     * binder 调用——系统繁忙时（应用启动、旋转、解锁）单次调用可能被堵
     * 几百毫秒，1Hz 节奏把主线程持续占满，实测症状：横屏应用进不去、
     * 锁屏界面无响应只能强重启。现在读取在后台线程做，主线程只收结果。
     */
    private void evaluate() {
        if (stripView == null) {
            return;
        }
        // 熄屏/锁屏：横条不可见也不需要任何工作。采集信号照发（自带去重，
        // 只在状态变化时真正下发一次），让模块进程的 root 采集也停下来。
        if (powerManager != null && !powerManager.isInteractive()) {
            signalCollector(false);
            return;
        }
        if (keyguardManager != null && keyguardManager.isKeyguardLocked()) {
            signalCollector(false);
            return;
        }

        // 请求刷新设置快照。真正的 query 在 PrefsSnapshot 自己的后台线程做。
        // 这是 RemotePreferences 不可用的机型（实测澎湃 OS 4）拿到设置的途径。
        PrefsSnapshot.requestRefresh();
        // Settings.Global 通道：system uid 直接读，每轮都刷得起
        SettingsChannel.refresh(context);

        // 全部配置读取在后台线程完成
        final boolean cFps = NexusBridge.isStripShowFps();
        final boolean cGraph = NexusBridge.isStripShowGraph();
        final boolean cGpu = NexusBridge.isStripShowGpu();
        final boolean cCpu = NexusBridge.isStripShowCpu();
        final boolean cRam = NexusBridge.isStripShowRam();
        final boolean cBat = NexusBridge.isStripShowBat();
        final boolean cClock = NexusBridge.isStripShowClock();
        final boolean cCpuTemp = NexusBridge.isStripShowCpuTemp();
        final boolean cGpuTemp = NexusBridge.isStripShowGpuTemp();
        final boolean cBatTemp = NexusBridge.isStripShowBatTemp();
        final float cFontScale = NexusBridge.getStripFontScale();
        final float cBgAlpha = NexusBridge.getStripBgAlpha();
        final float cLineIntensity = NexusBridge.getStripLineIntensity();
        final int cLineColor = NexusBridge.getStripLineColor();
        final boolean c24h = NexusBridge.isClock24h();
        final boolean show = isForegroundAllowed();
        final int wakeSec = NexusBridge.getWakeIntervalSec();
        final int cInterval = NexusBridge.getUpdateIntervalMs();

        // 窗口增删和 View 操作收回主线程
        mainHandler.post(() -> {
            if (stripView == null) {
                return;
            }
            stripView.setCollectInterval(cInterval);
            boolean changed = stripView.applyConfig(
                    cFps, cGraph, cGpu, cCpu, cRam, cBat,
                    cClock, cCpuTemp, cGpuTemp, cBatTemp,
                    cFontScale, cBgAlpha, cLineIntensity, cLineColor, c24h);
            if (changed) {
                stripView.invalidate();
            }
            if (show && !attached) {
                // ADDING / REMOVING 期间静默跳过：两条驱动路径（每秒轮询 +
                // ACTION_CONFIGURATION_CHANGED 广播）可能同时进来，非稳态时动手
                // 就是重影的来源
                if (windowState == STATE_IDLE) {
                    addToWindow();
                }
            } else if (!show && attached) {
                if (windowState == STATE_IDLE) {
                    removeFromWindow();
                }
            }
        });

        // 无条件同步，不放在上面的状态迁移分支里：开机后要是开关本来就关着，
        // 横条从来没添加过，也就谈不上「移除」这个迁移，可模块进程的采集默认是
        // 开着的，得靠这里把它关掉。signalCollector 自己会去重。
        signalCollector(show);

        // 后台唤醒：模块进程被 ROM 清掉时，按用户设的间隔把它拉回来，
        // 否则 GPU 数据会一直停在进程被杀之前的值
        wakeModuleIfDue(show, wakeSec);
    }

    /**
     * 通知模块进程启停 root GPU 采集。
     *
     * SystemUI 这边的 StripView 会随 detach 停掉自己的采集线程，但模块 App 进程
     * 里的 GpuCollectorWorker 是另一个进程的常驻 su 读取循环，不受 SystemUI 影响。
     * 横条隐藏时不通知它停，su 进程就会继续每秒读 sysfs，这正是「关了显示，
     * 后台还是耗电」的来源。
     *
     * 状态没变一般就不重复下发，免得每秒一次跨进程 query。例外是「需要采集」
     * 这个状态，每 Constants.Overlay#SIGNAL_REPEAT_TICKS 轮会复发一次当心跳：
     * 模块进程被系统回收后重启时默认不采集，靠这个心跳把它拉回来。
     *
     * 「暂停」不复发。模块进程重启后本来就是暂停状态，没什么漂移要纠正；在这儿
     * 复发 pause 反而会把已经被回收的模块进程反复重新拉起来，比省下的那点还费电。
     */
    private void signalCollector(boolean collecting) {
        boolean sameAsLast = lastCollectSignal != null && lastCollectSignal == collecting;
        if (sameAsLast) {
            if (!collecting) {
                return;
            }
            if (++signalTicks < Constants.Overlay.SIGNAL_REPEAT_TICKS) {
                return;
            }
        }
        signalTicks = 0;
        lastCollectSignal = collecting;
        CollectorSignal.send(context, collecting);
    }

    /**
     * 定时唤醒模块进程（v1.8.11）。
     *
     * 为什么需要：GPU 数据由模块 App 进程用 root 读出来再回传，进程被清掉数据就停更。
     * ColorOS 在最近任务里点「全部清除」会把应用置为 stopped 状态，这种状态下
     * 系统只放行带 FLAG_INCLUDE_STOPPED_PACKAGES 的广播。划卡只是普通杀进程、
     * 不置 stopped，所以划卡没事，只有全部清除后才需要这条。
     *
     * 频率由用户设定，0 表示关闭、只用原来的心跳。
     * 只在横条显示时才唤醒，隐藏时唤起来没人看，白耗电。
     */
    private void wakeModuleIfDue(boolean showing, int intervalSec) {
        if (!showing) {
            wakeElapsedTicks = 0;
            return;
        }
        if (intervalSec <= 0) {
            wakeElapsedTicks = 0;
            return;
        }
        // watch 循环每 WATCH_INTERVAL_MS 一轮，据此换算成需要等多少轮
        int ticksNeeded = (int) Math.max(1,
                intervalSec * 1000L / Constants.Overlay.WATCH_INTERVAL_MS);
        if (++wakeElapsedTicks < ticksNeeded) {
            return;
        }
        wakeElapsedTicks = 0;
        CollectorSignal.wakeModule(context);
        // 顺带确认采集在跑：模块进程刚被广播唤起时，需求还没打开
        CollectorSignal.send(context, true);
    }

    /**
     * 该不该显示：唯一的显示逻辑就是应用过滤。
     *
     * 过滤关（默认）恒显示；开了之后白名单为空 → 一直隐藏（用户开了过滤却
     * 一个应用没选，按字面意思办）；读不到前台包名 → 放行。判定手段失效时
     * 宁可显示，也别让横条无声无息地消失，让人以为模块坏了。
     */
    private boolean isForegroundAllowed() {
        if (!NexusBridge.isAppFilterEnabled()) {
            return true;
        }
        Set<String> whitelist = NexusBridge.getWhitelist();
        if (whitelist.isEmpty()) {
            return false;
        }
        String foreground = foregroundTracker.getForegroundPackage();
        if (foreground == null) {
            return true;
        }
        return whitelist.contains(foreground);
    }

    private void addToWindow() {
        if (windowManager == null || stripView == null) {
            windowState = STATE_IDLE;
            return;
        }
        // 幂等：View 已有 parent 是物理事实，比任何标志位都可靠。
        // 即便状态机被异常路径带偏，这一条也能拦住重复 add
        if (stripView.getParent() != null) {
            attached = true;
            windowState = STATE_IDLE;
            return;
        }
        if (params == null) {
            params = buildParams(Constants.Overlay.WINDOW_TYPES[0]);
        }
        windowState = STATE_ADDING;
        try {
            windowManager.addView(stripView, params);
            attached = true;
        } catch (Throwable t) {
            LogUtils.w("MonitorOverlay re-add failed", t);
        } finally {
            windowState = STATE_IDLE;
        }
    }

    private void removeFromWindow() {
        if (stripView == null || windowManager == null) {
            attached = false;
            windowState = STATE_IDLE;
            return;
        }
        // 幂等：没有 parent 说明本来就不在窗口里
        if (stripView.getParent() == null) {
            attached = false;
            windowState = STATE_IDLE;
            return;
        }
        windowState = STATE_REMOVING;
        try {
            // 用 removeViewImmediate 而不是 removeView：它同步等 View 真正脱离
            // ViewRootImpl。重影场景下移除必须立刻生效，否则紧接着的 addView
            // 会与还没退场的旧窗口短暂并存
            windowManager.removeViewImmediate(stripView);
        } catch (Throwable t) {
            LogUtils.w("MonitorOverlay removeView failed", t);
        } finally {
            attached = false;
            windowState = STATE_IDLE;
        }
    }

    /**
     * Strip 窗口参数：宽 MATCH_PARENT（横条从屏幕最左排到最右，圆角屏避让靠
     * view 内部的 startPad/endPad）、高 WRAP_CONTENT。
     */
    private WindowManager.LayoutParams buildParams(int type) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT);

        // 贴屏幕最顶端：y = 0 配合 FLAG_LAYOUT_IN_SCREEN / FLAG_LAYOUT_NO_LIMITS，
        // 竖屏横屏都紧贴各自方向下的物理屏幕上边缘
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.windowAnimations = 0;
        lp.setTitle(Constants.Overlay.WINDOW_TITLE);

        // 允许画进刘海/挖孔区域，不然横屏可能被 inset 推出可视范围
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        // 脱离系统 insets，才能稳稳停在屏幕最顶部
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                lp.setFitInsetsTypes(0);
            } catch (Throwable ignored) {
            }
        }
        return lp;
    }

    private void registerConfigReceiver() {
        if (configReceiver != null) {
            return;
        }
        configReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                // 旋转后 Configuration 可能晚一点才更新，推迟一拍再判定。
                // 判定跑在后台线程，post 到 bgHandler 而不是主线程
                if (bgHandler != null) {
                    bgHandler.post(MonitorOverlay.this::evaluate);
                }
            }
        };
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
            context.registerReceiver(configReceiver, filter);
        } catch (Throwable t) {
            LogUtils.w("MonitorOverlay config receiver register failed", t);
            configReceiver = null;
        }
    }

    private void unregisterConfigReceiver() {
        if (configReceiver == null) {
            return;
        }
        try {
            context.unregisterReceiver(configReceiver);
        } catch (Throwable ignored) {
        }
        configReceiver = null;
    }
}
