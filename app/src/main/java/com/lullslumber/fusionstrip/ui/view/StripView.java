package com.lullslumber.fusionstrip.ui.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.View;

import com.lullslumber.fusionstrip.bridge.FpsSyncBridge;
import com.lullslumber.fusionstrip.collector.PerformanceCollector;
import com.lullslumber.fusionstrip.constant.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Strip — 一条撑满屏幕宽的单行性能横条。
 *
 * === DroidDeck Strip 复刻版（按需求精简）===
 * 布局与配色对齐 DroidDeck DroidDeckHudView.buildStrip()（PR #1935，GPL-3.0，经 Bannerlator 转手）。
 * 从左到右：
 *   [FPS(发光) fps] [FT ms] [帧图] [GPU %] [GPU.T °] [CPU %] [CPU.T °]
 *   [RAM %] [BAT % W LEFT] ...... [时钟●钉右]
 * 放不下的从尾部（优先级最低）开始丢弃；时钟在最右端预留空间，永不因省略而移动。
 *
 * 已按需求去掉：lows（1%/0.1%/0.01%）、HDR、分辨率/DISP 占位、DroidDeck logo。
 * cfgFt 开关恢复为其原始语义「帧时间 FT」（1000/fps，与 FPS 同源，真实可读）。
 *
 * 数据源差异（原生安卓 vs DroidDeck 的 Winlator/Wayland 容器）：
 * - 帧图画的是 1Hz 的 FPS 历史（TimeStats 差分），不是逐帧 frametime——数据源硬上限；
 * - GPU.T：PerformanceData 无该字段，显示 "--"，保留元素位但不伪造数据；
 * - VRAM/DISP/HDR：原生无对应数据源，已按需求去掉。
 *
 * 渲染优化（DroidDeck PR #2235 hybrid）：帧图与背景各自画进可复用 bitmap，
 * 尺寸/颜色变化才重建，onDraw 只 drawBitmap；graph 重绘 66ms 限频。
 *
 * 跑在 SystemUI 进程，由 ModuleMain 注入独立悬浮窗；隐藏时零采集开销。
 */
public class StripView extends View {

    // ---- 配色：对齐 DroidDeck Strip 的语义色 ----
    private static final int COL_VALUE = 0xFFFFFFFF;   // 数值：白
    private static final int COL_DIM = 0xB3FFFFFF;     // 单位/标签：半透白
    private static final int COL_FPS = 0xFF7FD4FF;     // FPS / FT：亮蓝
    private static final int COL_GPU = 0xFFFFB74D;     // GPU：琥珀
    private static final int COL_CPU = 0xFF64B5F6;     // CPU：蓝
    private static final int COL_RAM = 0xFF81C784;     // RAM：绿
    private static final int COL_BAT = 0xFFFF8A80;     // 电池：珊瑚红
    private static final int COL_GRAPH = 0xFF8AB4F8;   // 帧图：淡蓝

    private static final int LINE_COLOR_DEFAULT = 0xFFFFB74D;
    private static final float LINE_MAX_DP = 3.5f;
    private static final float BG_RADIUS_SP = 8f;

    // ---- 布局常量：逐项对齐 DroidDeck buildStrip() ----
    private static final float ROW_SP = 11.5f;
    private static final float FPS_SP = 15f;           // 发光主视觉
    private static final float UNIT_SCALE = 0.8f;
    private static final float PAD_SP = 10f;
    private static final float END_PAD_SP = 16f;
    private static final float GROUP_GAP_SP = 13f;
    private static final float STRIP_H_SP = 26f;
    private static final float GRAPH_W_SP = 72f;
    private static final float GRAPH_H_RATIO = 0.55f;
    private static final float GRAPH_STROKE_SP = 1.6f;
    private static final float GRAPH_AMPLITUDE = 0.5f;
    private static final long STRIP_GRAPH_REDRAW_MS = 66L;
    private static final int HIST_CAP = 60;            // 60 个 1Hz 样本 = 60 秒

    /** 一段文字：内容、颜色、字号(px)、画完后的间隙、是否发光 */
    private static final class Span {
        final String text;
        final int color;
        final float sizePx;
        final float gapAfter;
        final boolean glow;
        Span(String text, int color, float sizePx, float gapAfter) {
            this(text, color, sizePx, gapAfter, false);
        }
        Span(String text, int color, float sizePx, float gapAfter, boolean glow) {
            this.text = text;
            this.color = color;
            this.sizePx = sizePx;
            this.gapAfter = gapAfter;
            this.glow = glow;
        }
    }

    /** 一个优先级 piece：整段宽度 + spans（spans=null 表示帧图 marker） */
    private static final class Piece {
        final float width;
        final List<Span> spans;
        Piece(float width, List<Span> spans) {
            this.width = width;
            this.spans = spans;
        }
    }

    /** 摆好位置的文字。注意构造参数顺序：(text, x, baseline, color, sizePx, glow) */
    private static final class Glyph {
        final String text;
        final float x;
        final float baseline;
        final int color;
        final float sizePx;
        final boolean glow;
        Glyph(String text, float x, float baseline, int color, float sizePx, boolean glow) {
            this.text = text;
            this.x = x;
            this.baseline = baseline;
            this.color = color;
            this.sizePx = sizePx;
            this.glow = glow;
        }
    }

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint graphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path graphPath = new Path();

    private final RuntimeEstimator runtime;

    // ---- Strip 配置（App 端每秒下发，applyConfig 热更）----
    private boolean cfgFps = true;
    private boolean cfgFt = true;      // 帧时间 FT（1000/fps）
    private boolean cfgGraph = true;
    private boolean cfgGpu = true;
    private boolean cfgCpu = true;
    private boolean cfgRam = true;
    private boolean cfgBat = true;
    private boolean cfgClock = true;
    private float cfgFontScale = 1.0f;
    private float cfgBgAlpha = 0.63f;
    private float cfgLineIntensity = 0.5f;
    private int cfgLineColor = LINE_COLOR_DEFAULT;

    private final List<Glyph> glyphs = new ArrayList<>();
    private RectF graphRect;
    private float bgRadiusPx;
    private float contentH;

    private final float[] fpsHistory = new float[HIST_CAP];
    private int histCount;
    private int histHead;
    private float lastFps = -1f;

    private String clockText = "";
    private String lastClockKey = "";
    private boolean clock24h = true;

    private long lastRebuildAt;

    private Bitmap graphBitmap;
    private int graphBw;
    private int graphBh;
    private Bitmap bgBitmap;
    private int bgBw;
    private int bgBh;
    private float bgLineW = -1f;
    private int bgLineColor = 0;
    private boolean bgLineAtTop;

    private PerformanceCollector collector;

    public StripView(Context context) {
        super(context);
        clock24h = DateFormat.is24HourFormat(context);
        textPaint.setTypeface(resolveTypeface());
        glowPaint.setTypeface(resolveTypeface());
        glowPaint.setStyle(Paint.Style.FILL);
        graphPaint.setStyle(Paint.Style.STROKE);
        android.os.BatteryManager bm = (android.os.BatteryManager)
                context.getSystemService(Context.BATTERY_SERVICE);
        runtime = new RuntimeEstimator(bm);
        collector = new PerformanceCollector(context, this::updateData);
    }

    /** 签名保持 13 参数不变——MonitorOverlay 调用链零改动 */
    public boolean applyConfig(boolean fps, boolean ft, boolean graph,
                               boolean gpu, boolean cpu, boolean ram, boolean bat,
                               boolean clock, float fontScale, float bgAlpha,
                               float lineIntensity, int lineColor, boolean use24h) {
        boolean changed = false;
        if (cfgFps != fps) { cfgFps = fps; changed = true; }
        if (cfgFt != ft) { cfgFt = ft; changed = true; }
        if (cfgGraph != graph) { cfgGraph = graph; changed = true; }
        if (cfgGpu != gpu) { cfgGpu = gpu; changed = true; }
        if (cfgCpu != cpu) { cfgCpu = cpu; changed = true; }
        if (cfgRam != ram) { cfgRam = ram; changed = true; }
        if (cfgBat != bat) { cfgBat = bat; changed = true; }
        if (cfgClock != clock) { cfgClock = clock; changed = true; }
        if (Math.abs(cfgFontScale - fontScale) > 0.001f) { cfgFontScale = fontScale; changed = true; }
        if (Math.abs(cfgBgAlpha - bgAlpha) > 0.001f) { cfgBgAlpha = bgAlpha; changed = true; }
        if (Math.abs(cfgLineIntensity - lineIntensity) > 0.001f) { cfgLineIntensity = lineIntensity; changed = true; }
        if (cfgLineColor != lineColor) { cfgLineColor = lineColor; changed = true; }
        if (clock24h != use24h) { clock24h = use24h; lastClockKey = ""; changed = true; }
        return changed;
    }

    private static Typeface resolveTypeface() {
        return Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (collector != null) collector.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (collector != null) collector.stop();
    }

    public void setCollecting(boolean collecting) {
        if (collector == null) return;
        if (collecting) collector.start(); else collector.stop();
    }

    public void setClock24h(boolean use24h) {
        if (clock24h == use24h) return;
        clock24h = use24h;
        lastClockKey = "";
    }

    public void updateData(PerformanceCollector.PerformanceData data) {
        long now = System.currentTimeMillis();
        if (now - lastRebuildAt < STRIP_GRAPH_REDRAW_MS) return;
        lastRebuildAt = now;

        pushFps(data.fps);
        runtime.update(data);
        FpsSyncBridge.publish(getContext(), data.fps, data.fpsTag);
        rebuild(data, density(), now);
        invalidate();
    }

    private void pushFps(float fps) {
        if (fps > 0f) lastFps = fps;
        fpsHistory[histHead] = Math.max(fps, 0f);
        histHead = (histHead + 1) % HIST_CAP;
        if (histCount < HIST_CAP) histCount++;
    }

    private void collectHistory(float[] out) {
        int start = (histHead - histCount + HIST_CAP) % HIST_CAP;
        for (int i = 0; i < histCount; i++) out[i] = fpsHistory[(start + i) % HIST_CAP];
    }

    // ---- 布局：DroidDeck buildStrip 的顺序与优先级 ----
    private void rebuild(PerformanceCollector.PerformanceData d, float density, long now) {
        float px = sp(ROW_SP * cfgFontScale, density);
        float fpsPx = sp(FPS_SP * cfgFontScale, density);
        float unitPx = px * UNIT_SCALE;
        float pad = sp(PAD_SP, density);
        float gap = sp(GROUP_GAP_SP, density);
        float endPad = sp(END_PAD_SP, density);
        bgRadiusPx = sp(BG_RADIUS_SP, density);

        textPaint.setTextSize(px);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float line = fm.descent - fm.ascent;
        float viewH = sp(STRIP_H_SP, density) * cfgFontScale;
        if (viewH < pad * 2f + line) viewH = pad * 2f + line;
        contentH = viewH;
        float mid = viewH / 2f;
        textPaint.setTextSize(fpsPx);
        Paint.FontMetrics fmf = textPaint.getFontMetrics();
        float baseline = mid + (-fmf.top) / 2f;

        float viewW = getWidth() > 0 ? getWidth()
                : getResources().getDisplayMetrics().widthPixels;

        // 时钟钉最右：先预留空间（DroidDeck 的关键设计）
        float right = viewW - endPad;
        String clock = cfgClock ? clockString(now) : "";
        float clockW = clock.isEmpty() ? 0f : measure(clock, px);

        List<Piece> pieces = new ArrayList<>();

        // 1) FPS（发光大字 + fps 单位）
        if (cfgFps) {
            List<Span> f = new ArrayList<>();
            f.add(new Span(formatFps(lastFps), COL_VALUE, fpsPx, px * 0.28f, true));
            f.add(new Span("fps", COL_DIM, unitPx, 0f));
            pieces.add(new Piece(runWidth(f), f));
        }

        // 2) 帧时间 FT（1000/fps，与 FPS 同源）
        if (cfgFt) {
            String ms = lastFps > 0f
                    ? String.format(Locale.US, "%.1f", 1000f / Math.max(lastFps, 1f)) : "--";
            List<Span> ft = new ArrayList<>();
            ft.add(new Span("FT", COL_FPS, unitPx, unitPx * 0.5f));
            ft.add(new Span(ms, COL_VALUE, px, px * 0.28f));
            ft.add(new Span("ms", COL_DIM, unitPx, 0f));
            pieces.add(new Piece(runWidth(ft), ft));
        }

        // 3) 帧图（FPS 历史）
        if (cfgGraph) {
            pieces.add(new Piece(sp(GRAPH_W_SP, density), null));
        }

        // 4) GPU 占用 + GPU 温度（无数据源显示 --，保留元素位）
        if (cfgGpu) {
            pieces.add(metricPiece("GPU", COL_GPU,
                    String.valueOf(d.gpuTotalUsage), "%", px, unitPx));
            pieces.add(metricPiece("GPU.T", COL_GPU, "--", "°", px, unitPx));
        }

        // 5) CPU 占用 + CPU 温度
        if (cfgCpu) {
            pieces.add(metricPiece("CPU", COL_CPU,
                    String.valueOf((int) d.cpuTotalUsage), "%", px, unitPx));
            pieces.add(metricPiece("CPU.T", COL_CPU, tempText(d.cpuTemp), "°", px, unitPx));
        }

        // 6) RAM
        if (cfgRam) {
            pieces.add(metricPiece("RAM", COL_RAM,
                    String.valueOf((int) d.ramUsagePercent), "%", px, unitPx));
        }

        // 7) BAT：电量% + 功率W + 续航LEFT
        if (cfgBat) {
            List<Span> bat = new ArrayList<>();
            bat.add(new Span("BAT", COL_BAT, unitPx, unitPx * 0.5f));
            boolean any = false;
            if (d.batteryPercent >= 0) {
                bat.add(new Span(String.valueOf(d.batteryPercent), COL_VALUE, px, px * 0.28f));
                bat.add(new Span("%", COL_DIM, unitPx, unitPx * 0.5f));
                any = true;
            }
            if (d.batteryPowerW != 0f) {
                bat.add(new Span(String.format(Locale.US, "%.1f", Math.abs(d.batteryPowerW)),
                        COL_VALUE, px, px * 0.28f));
                bat.add(new Span("W", COL_DIM, unitPx, unitPx * 0.5f));
                any = true;
            }
            String left = runtime.text();
            if (left != null) {
                bat.add(new Span("LEFT", COL_BAT, unitPx, unitPx * 0.5f));
                bat.add(new Span(left, COL_VALUE, px, 0f));
                any = true;
            }
            if (any) pieces.add(new Piece(runWidth(bat), bat));
        }

        // 依次摆放：放不下的从尾部开始丢；时钟已在右端预留，不参与省略
        glyphs.clear();
        graphRect = null;
        if (clockW > 0f) {
            glyphs.add(new Glyph(clock, viewW - endPad - clockW, baseline, COL_DIM, px, false));
            right -= clockW + gap;
        }
        float x = pad;
        float graphW = sp(GRAPH_W_SP, density);
        float graphH = viewH * GRAPH_H_RATIO;
        for (Piece p : pieces) {
            float nextGap = x > pad ? gap : 0f;
            if (x + nextGap + p.width > right) break;
            x += nextGap;
            if (p.spans != null) {
                for (Span s : p.spans) {
                    glyphs.add(new Glyph(s.text, x, baseline, s.color, s.sizePx, s.glow));
                    x += measure(s.text, s.sizePx) + s.gapAfter;
                }
            } else {
                graphRect = new RectF(x, mid - graphH / 2f, x + graphW, mid + graphH / 2f);
                x += p.width;
            }
        }

        if (getMeasuredHeight() != Math.round(viewH)) requestLayout();
    }

    private Piece metricPiece(String label, int labelColor, String value, String unit,
                              float px, float unitPx) {
        List<Span> l = new ArrayList<>();
        l.add(new Span(label, labelColor, unitPx, unitPx * 0.5f));
        l.add(new Span(value, COL_VALUE, px, px * 0.28f));
        if (!unit.isEmpty()) l.add(new Span(unit, COL_DIM, unitPx, 0f));
        return new Piece(runWidth(l), l);
    }

    private static String tempText(float c) {
        return Float.isNaN(c) ? "--" : String.format(Locale.US, "%.0f", c);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = density();
        float pad = sp(PAD_SP, density);
        textPaint.setTextSize(sp(ROW_SP, density));
        float line = textPaint.getFontMetrics().descent - textPaint.getFontMetrics().ascent;
        int w = MeasureSpec.getSize(widthMeasureSpec);
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.EXACTLY || w <= 0) {
            w = getResources().getDisplayMetrics().widthPixels;
        }
        int h = Math.round(contentH > 0f ? contentH : pad * 2f + line);
        setMeasuredDimension(Math.max(w, 1), Math.max(h, 1));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        drawBackground(canvas);
        for (int i = 0; i < glyphs.size(); i++) {
            Glyph g = glyphs.get(i);
            textPaint.setTextSize(g.sizePx);
            textPaint.setColor(g.color);
            if (g.glow) {
                glowPaint.setTextSize(g.sizePx);
                glowPaint.setColor(g.color);
                glowPaint.setAlpha(60);
                canvas.drawText(g.text, g.x, g.baseline, glowPaint);
            }
            canvas.drawText(g.text, g.x, g.baseline, textPaint);
        }
        drawGraphCached(canvas);
    }

    private int bgColor() {
        int alpha = Math.round(cfgBgAlpha * 255f);
        return (alpha << 24);
    }

    private void drawBackground(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int color = bgColor();
        boolean sizeChanged = bgBitmap == null || bgBw != w || bgBh != h;
        boolean colorChanged = !sizeChanged && bgBitmap.getPixel(w / 2, h / 2) != color;
        float lineW = lineThicknessPx();
        boolean lineAtTop = isStripAtBottom();
        boolean lineChanged = bgBitmap != null
                && (bgLineW != lineW || bgLineColor != cfgLineColor || bgLineAtTop != lineAtTop);
        if (sizeChanged || colorChanged || lineChanged) {
            Bitmap old = bgBitmap;
            bgBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bgBw = w;
            bgBh = h;
            bgLineW = lineW;
            bgLineColor = cfgLineColor;
            bgLineAtTop = lineAtTop;
            Canvas bc = new Canvas(bgBitmap);
            bgPaint.setColor(color);
            bc.drawRoundRect(0f, 0f, w, h, bgRadiusPx, bgRadiusPx, bgPaint);
            if (lineW > 0f) {
                bgPaint.setColor(applyLineAlpha(cfgLineColor));
                if (lineAtTop) {
                    bc.drawRect(bgRadiusPx, 0f, w - bgRadiusPx, lineW, bgPaint);
                } else {
                    bc.drawRect(bgRadiusPx, h - lineW, w - bgRadiusPx, h, bgPaint);
                }
            }
            if (old != null) old.recycle();
        }
        canvas.drawBitmap(bgBitmap, 0f, 0f, null);
    }

    private float lineThicknessPx() {
        float i = cfgLineIntensity;
        if (i <= 0f) return 0f;
        float stroke = i * LINE_MAX_DP * getResources().getDisplayMetrics().density;
        return Math.max(stroke / 2f, getResources().getDisplayMetrics().density);
    }

    private int applyLineAlpha(int color) {
        int alpha = Math.min(255, Math.round(110f + 145f * cfgLineIntensity));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    private boolean isStripAtBottom() {
        int[] pos = new int[2];
        getLocationOnScreen(pos);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        return screenH > 0 && pos[1] + getHeight() / 2f > screenH / 2f;
    }

    private void drawGraphCached(Canvas canvas) {
        if (graphRect == null) return;
        int bw = Math.max(1, Math.round(graphRect.width()));
        int bh = Math.max(1, Math.round(graphRect.height()));
        if (graphBitmap == null || graphBw != bw || graphBh != bh) {
            Bitmap old = graphBitmap;
            graphBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            graphBw = bw;
            graphBh = bh;
            if (old != null) old.recycle();
        }
        graphBitmap.eraseColor(Color.TRANSPARENT);
        Canvas bc = new Canvas(graphBitmap);

        float[] vals = new float[histCount];
        collectHistory(vals);
        int n = 0;
        for (int i = 0; i < histCount; i++) if (vals[i] > 0f) vals[n++] = vals[i];
        if (n >= 2) {
            float mean = 0f;
            for (int i = 0; i < n; i++) mean += vals[i];
            mean /= n;
            float dev = 0.5f;
            for (int i = 0; i < n; i++) {
                float d2 = Math.abs(vals[i] - mean);
                if (d2 > dev) dev = d2;
            }
            graphPaint.setStrokeWidth(sp(GRAPH_STROKE_SP, density()));
            graphPaint.setColor(COL_GRAPH);
            graphPath.reset();
            float step = bw / (n - 1);
            float half = bh / 2f * GRAPH_AMPLITUDE;
            for (int i = 0; i < n; i++) {
                float gx = i * step;
                float gy = Math.max(0f, Math.min(bh,
                        bh / 2f - (vals[i] - mean) / dev * half));
                if (i == 0) graphPath.moveTo(gx, gy); else graphPath.lineTo(gx, gy);
            }
            bc.drawPath(graphPath, graphPaint);
        }
        canvas.drawBitmap(graphBitmap, graphRect.left, graphRect.top, null);
    }

    private float density() {
        return getResources().getDisplayMetrics().scaledDensity;
    }

    private static float sp(float sp, float scaledDensity) {
        return sp * scaledDensity;
    }

    private float runWidth(List<Span> spans) {
        float w = 0f;
        for (Span s : spans) w += measure(s.text, s.sizePx) + s.gapAfter;
        return w;
    }

    private float measure(String text, float sizePx) {
        if (text == null || text.isEmpty()) return 0f;
        textPaint.setTextSize(sizePx);
        return textPaint.measureText(text);
    }

    private String formatFps(float fps) {
        if (fps < 0f) return "--";
        if (fps >= Constants.Config.FPS_INTEGER_THRESHOLD) {
            return String.format(Locale.US, "%.0f", fps);
        }
        return String.format(Locale.US, "%.1f", fps);
    }

    private String clockString(long now) {
        String key = String.valueOf(now / 60000L);
        if (clockText.isEmpty() || !key.equals(lastClockKey)) {
            lastClockKey = key;
            clockText = DateFormat.format(clock24h
                    ? Constants.Modules.CLOCK_PATTERN_24H
                    : Constants.Modules.CLOCK_PATTERN_12H, now).toString();
        }
        return clockText;
    }

    /** 剩余续航估算：剩余电荷 ÷ 平滑放电电流（DroidDeck PR #2573 同位功能） */
    private static final class RuntimeEstimator {
        private static final long CHARGE_REFRESH_MS = 30_000L;
        private static final float EMA_ALPHA = 0.1f;
        private static final float MIN_TRUST_MA = 80f;
        private static final float MAX_HOURS = 99f;

        private final android.os.BatteryManager batteryManager;
        private float emaCurrentMa = 0f;
        private boolean primed;
        private long lastChargeReadAt;
        private float chargeMah = -1f;

        RuntimeEstimator(android.os.BatteryManager bm) { batteryManager = bm; }

        void update(PerformanceCollector.PerformanceData d) {
            float a = Math.abs(d.batteryCurrentMa);
            boolean discharging = d.batteryCurrentMa < -5f;
            if (a > 0f) {
                if (!primed) { emaCurrentMa = a; primed = true; }
                else if (discharging) { emaCurrentMa += (a - emaCurrentMa) * EMA_ALPHA; }
                else { emaCurrentMa *= (1f - EMA_ALPHA * 0.5f); }
            }
            long now = System.currentTimeMillis();
            if (now - lastChargeReadAt >= CHARGE_REFRESH_MS && batteryManager != null) {
                lastChargeReadAt = now;
                try {
                    int uAh = batteryManager.getIntProperty(
                            android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                    if (uAh > 0) chargeMah = uAh / 1000f;
                } catch (Throwable ignored) { }
            }
        }

        String text() {
            if (chargeMah <= 0f || !primed) return null;
            if (emaCurrentMa < MIN_TRUST_MA) return "CHG";
            float hours = chargeMah / emaCurrentMa;
            if (hours > MAX_HOURS) return "99h+";
            int h = (int) hours;
            int m = Math.round((hours - h) * 60f);
            if (m >= 60) { h++; m -= 60; }
            return String.format(Locale.US, "%dh %02dm", h, m);
        }
    }
}
