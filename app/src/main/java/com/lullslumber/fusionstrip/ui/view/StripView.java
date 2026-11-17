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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Strip — 撑满屏幕宽的单行性能横条。DroidDeck Strip 全元素复刻。
 *
 * 元素（DroidDeck ROWS[STRIP] 顺序，逐项对齐 buildStrip/reading/switchLabel）：
 *   [标记] [API] [FPS发光] [帧图] [FT] [1%] [0.1%] [0.01%] [GPU%] [GPU.T]
 *   [CPU%] [CPU.T] [VRAM] [RAM] [BAT%] [BAT.T] [BAT TIME] [PWR]
 *   [DISP] [HDR] [MODEL] ...... [时钟钉右]
 * 每项都在：原生无数据源的显示 "--"/"—"/"off" 占位，不省略元素本身。
 *
 * 渲染沿用 DroidDeck PR #2235 hybrid：帧图与背景各自画进可复用 bitmap，
 * 尺寸/颜色变化才重建，onDraw 只 drawBitmap；66ms 重绘限频。
 */
public class StripView extends View {

    // 配色：逐字对齐 DroidDeck DroidDeckHudView（不是自造色）
    private static final int COL_VALUE = 0xFFF2F5F9;   // colValue
    private static final int COL_DIM = 0xFF9AA4B2;     // colDim
    private static final int COL_FPS = 0xFF1A9FFF;     // accent / colFps
    private static final int COL_GPU = 0xFF5EE08A;     // colGpu 绿
    private static final int COL_CPU = 0xFF58A6FF;     // colCpu 蓝
    private static final int COL_RAM = 0xFFFF7BC0;     // colRam 粉
    private static final int COL_BAT = 0xFFFFAB5E;     // colBat 橙
    private static final int COL_VRAM = 0xFFC98BFF;    // colVram 紫
    private static final int COL_LO = 0xFFE4E8EE;      // colLo
    private static final int COL_DISP = 0xFF4DD0E1;    // colDisp 青
    private static final int COL_HDR = 0xFFFFD54F;     // colHdr 黄
    private static final int COL_GRAPH = 0xFF5CB8FF;   // colGraph（DroidDeck 帧图/发光色）

    /** DroidDeck surfaceRgb = 0x121417（深灰，不是纯黑） */
    private static final int SURFACE_RGB = 0x121417;
    private static final int LINE_COLOR_DEFAULT = 0xFF1A9FFF;   // accent
    private static final float LINE_MAX_DP = 3.5f;
    private static final float BG_RADIUS_SP = 8f;

    // 布局常量对齐 DroidDeck buildStrip
    private static final float ROW_SP = 11.5f;
    private static final float FPS_SP = 15f;
    private static final float UNIT_SCALE = 0.8f;
    private static final float PAD_SP = 10f;
    private static final float END_PAD_SP = 16f;
    private static final float GROUP_GAP_SP = 13f;
    private static final float STRIP_H_SP = 26f;
    private static final float GRAPH_W_SP = 72f;
    private static final float GRAPH_H_RATIO = 0.55f;
    private static final float MARK_H_SP = 13f;
    private static final float GRAPH_STROKE_SP = 1.6f;
    private static final float GRAPH_AMPLITUDE = 0.5f;
    private static final long STRIP_GRAPH_REDRAW_MS = 66L;
    private static final int HIST_CAP = 60;

    private static final class Span {
        final String text; final int color; final float sizePx; final float gapAfter; final boolean glow;
        Span(String text, int color, float sizePx, float gapAfter) { this(text, color, sizePx, gapAfter, false); }
        Span(String text, int color, float sizePx, float gapAfter, boolean glow) {
            this.text = text; this.color = color; this.sizePx = sizePx; this.gapAfter = gapAfter; this.glow = glow;
        }
    }
    private static final class Piece {
        final float width; final List<Span> spans;
        Piece(float width, List<Span> spans) { this.width = width; this.spans = spans; }
    }
    /** 构造顺序：(text, x, baseline, color, sizePx, glow) */
    private static final class Glyph {
        final String text; final float x; final float baseline; final int color; final float sizePx; final boolean glow;
        Glyph(String text, float x, float baseline, int color, float sizePx, boolean glow) {
            this.text = text; this.x = x; this.baseline = baseline; this.color = color; this.sizePx = sizePx; this.glow = glow;
        }
    }

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint graphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path graphPath = new Path();
    private final RuntimeEstimator runtime;

    private boolean cfgFps = true, cfgFt = true, cfgGraph = true, cfgGpu = true;
    private boolean cfgCpu = true, cfgRam = true, cfgBat = true, cfgClock = true;
    private float cfgFontScale = 1.0f, cfgBgAlpha = 0.63f, cfgLineIntensity = 0.5f;
    private int cfgLineColor = LINE_COLOR_DEFAULT;

    private final List<Glyph> glyphs = new ArrayList<>();
    private RectF graphRect;
    private RectF markRect;
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
    private int graphBw, graphBh;
    private Bitmap bgBitmap;
    private int bgBw, bgBh;
    private float bgLineW = -1f;
    private int bgLineColor = 0;
    private boolean bgLineAtTop;
    private PerformanceCollector collector;

    public StripView(Context context) {
        super(context);
        clock24h = DateFormat.is24HourFormat(context);
        textPaint.setTypeface(resolveTypeface());
        graphPaint.setStyle(Paint.Style.STROKE);
        markPaint.setStyle(Paint.Style.FILL);
        markPaint.setAntiAlias(true);
        android.os.BatteryManager bm = (android.os.BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        runtime = new RuntimeEstimator(bm);
        collector = new PerformanceCollector(context, this::updateData);
    }

    /** 13 参数签名保持不变，MonitorOverlay 调用链零改动 */
    public boolean applyConfig(boolean fps, boolean ft, boolean graph, boolean gpu, boolean cpu,
                               boolean ram, boolean bat, boolean clock, float fontScale,
                               float bgAlpha, float lineIntensity, int lineColor, boolean use24h) {
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

    private static Typeface resolveTypeface() { return Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (collector != null) collector.start(); }
    @Override protected void onDetachedFromWindow() { super.onDetachedFromWindow(); if (collector != null) collector.stop(); }
    public void setCollecting(boolean c) { if (collector == null) return; if (c) collector.start(); else collector.stop(); }
    public void setClock24h(boolean u) { if (clock24h == u) return; clock24h = u; lastClockKey = ""; }

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

    /** 从 FPS 历史按分位近似 low */
    private float lowFromHistory(float percentile) {
        if (histCount < 3) return lastFps > 0f ? lastFps : 0f;
        float[] vals = new float[histCount];
        collectHistory(vals);
        int n = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i] > 0f) vals[n++] = vals[i];
        if (n == 0) return 0f;
        Arrays.sort(vals, 0, n);
        int idx = Math.max(0, (int) Math.floor(n * percentile) - 1);
        return vals[idx];
    }

    /** 全元素布局：顺序严格对齐 DroidDeck ROWS[STRIP] */
    private void rebuild(PerformanceCollector.PerformanceData d, float density, long now) {
        float px = sp(ROW_SP * cfgFontScale, density);
        float fpsPx = sp(FPS_SP * cfgFontScale, density);
        float unitPx = px * UNIT_SCALE;
        float pad = sp(PAD_SP, density);
        float gap = sp(GROUP_GAP_SP, density);
        float endPad = sp(END_PAD_SP, density);
        float markH = sp(MARK_H_SP, density);
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
        float viewW = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;

        // 标记按需求去掉，起点从内边距开始
        float markW = 0f;
        float markLeft = pad;
        markRect = null;

        // 时钟钉最右
        float right = viewW - endPad;
        String clock = cfgClock ? clockString(now) : "";
        float clockW = clock.isEmpty() ? 0f : measure(clock, px);

        List<Piece> pieces = new ArrayList<>();

        // 1) FPS（发光大字，API 占位按需求去掉）
        if (cfgFps) {
            List<Span> f = new ArrayList<>();
            f.add(new Span(formatFps(lastFps), COL_VALUE, fpsPx, px * 0.28f, true));
            f.add(new Span("fps", COL_DIM, unitPx, 0f));
            pieces.add(new Piece(runWidth(f), f));
        }

        // 3) 帧图（FPS 历史）
        if (cfgGraph) pieces.add(new Piece(sp(GRAPH_W_SP, density), null));

        // 4) FT
        if (cfgFt) {
            String ms = lastFps > 0f ? String.format(Locale.US, "%.1f", 1000f / Math.max(lastFps, 1f)) : "--";
            List<Span> ft = new ArrayList<>();
            ft.add(new Span("FT", COL_FPS, unitPx, unitPx * 0.5f));
            ft.add(new Span(ms, COL_VALUE, px, px * 0.28f));
            ft.add(new Span("ms", COL_DIM, unitPx, 0f));
            pieces.add(new Piece(runWidth(ft), ft));
        }

        // 5) GPU% / GPU.T（lows 1%/0.1%/0.01% 按需求去掉，不加回）
        if (cfgGpu) {
            pieces.add(metricPiece("GPU", COL_GPU, String.valueOf(d.gpuTotalUsage), "%", px, unitPx));
            pieces.add(metricPiece("GPU.T", COL_GPU, "--", "°", px, unitPx));
        }

        // 7) CPU% / CPU.T
        if (cfgCpu) {
            pieces.add(metricPiece("CPU", COL_CPU, String.valueOf((int) d.cpuTotalUsage), "%", px, unitPx));
            pieces.add(metricPiece("CPU.T", COL_CPU, tempText(d.cpuTemp), "°", px, unitPx));
        }

        // 8) RAM（VRAM 按需求去掉）
        if (cfgRam) {
            pieces.add(metricPiece("RAM", COL_RAM, String.valueOf((int) d.ramUsagePercent), "%", px, unitPx));
        }

        // 9) BAT% / BAT.T / BAT TIME / PWR
        if (cfgBat) {
            pieces.add(metricPiece("BAT", COL_BAT,
                    d.batteryPercent >= 0 ? String.valueOf(d.batteryPercent) : "--", "%", px, unitPx));
            pieces.add(metricPiece("BAT.T", COL_BAT, tempText(d.batteryTemp), "°", px, unitPx));
            String left = runtime.text();
            pieces.add(metricPiece("BAT TIME", COL_BAT, left != null ? left : "—", "", px, unitPx));
            pieces.add(metricPiece("PWR", COL_BAT,
                    d.batteryPowerW != 0f ? String.format(Locale.US, "%.1f", Math.abs(d.batteryPowerW)) : "--",
                    "W", px, unitPx));
        }

        // DISP / HDR / MODEL 按需求去掉

        // 摆放：放不下的从尾部丢；时钟已预留不参与省略
        glyphs.clear();
        graphRect = null;
        if (clockW > 0f) {
            glyphs.add(new Glyph(clock, viewW - endPad - clockW, baseline, COL_DIM, px, false));
            right -= clockW + gap;
        }
        float x = markLeft;   // 无标记，从内边距起步
        float graphW = sp(GRAPH_W_SP, density);
        float graphH = viewH * GRAPH_H_RATIO;
        for (Piece p : pieces) {
            if (x + p.width > right) break;
            if (p.spans != null) {
                for (Span s : p.spans) {
                    glyphs.add(new Glyph(s.text, x, baseline, s.color, s.sizePx, s.glow));
                    x += measure(s.text, s.sizePx) + s.gapAfter;
                }
            } else {
                graphRect = new RectF(x, mid - graphH / 2f, x + graphW, mid + graphH / 2f);
                x += p.width + gap;
            }
            x += gap;
        }
        if (getMeasuredHeight() != Math.round(viewH)) requestLayout();
    }

    private Piece lowPiece(String label, float value, float px, float unitPx) {
        List<Span> l = new ArrayList<>();
        l.add(new Span(label, COL_LO, unitPx, unitPx * 0.5f));
        l.add(new Span(value > 0f ? String.format(Locale.US, "%.0f", value) : "--", COL_VALUE, px, 0f));
        return new Piece(runWidth(l), l);
    }

    private Piece metricPiece(String label, int labelColor, String value, String unit, float px, float unitPx) {
        List<Span> l = new ArrayList<>();
        l.add(new Span(label, labelColor, unitPx, unitPx * 0.5f));
        l.add(new Span(value, COL_VALUE, px, px * 0.28f));
        if (!unit.isEmpty()) l.add(new Span(unit, COL_DIM, unitPx, 0f));
        return new Piece(runWidth(l), l);
    }

    private static String tempText(float c) {
        return Float.isNaN(c) ? "--" : String.format(Locale.US, "%.0f", c);
    }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        float density = density();
        float pad = sp(PAD_SP, density);
        textPaint.setTextSize(sp(ROW_SP, density));
        float line = textPaint.getFontMetrics().descent - textPaint.getFontMetrics().ascent;
        int w = MeasureSpec.getSize(wSpec);
        if (MeasureSpec.getMode(wSpec) != MeasureSpec.EXACTLY || w <= 0) {
            w = getResources().getDisplayMetrics().widthPixels;
        }
        int h = Math.round(contentH > 0f ? contentH : pad * 2f + line);
        setMeasuredDimension(Math.max(w, 1), Math.max(h, 1));
    }

    @Override protected void onDraw(Canvas canvas) {
        drawBackground(canvas);
        drawMark(canvas);
        for (int i = 0; i < glyphs.size(); i++) {
            Glyph g = glyphs.get(i);
            textPaint.setTextSize(g.sizePx);
            textPaint.setColor(g.color);
            // DroidDeck：glow 用 setShadowLayer(sp(6),0,0,withAlpha(colGraph,217))，画完清除
            if (g.glow) textPaint.setShadowLayer(sp(6f, density()), 0f, 0f,
                    (217 << 24) | (COL_GRAPH & 0x00FFFFFF));
            canvas.drawText(g.text, g.x, g.baseline, textPaint);
            if (g.glow) textPaint.clearShadowLayer();
        }
        drawGraphCached(canvas);
    }

    /** 等效 DroidDeck 标记：D 形 + 圆球，纯 Path，零依赖 */
    private void drawMark(Canvas canvas) {
        if (markRect == null) return;
        float h = markRect.height();
        float left = markRect.left;
        float top = markRect.top;
        markPaint.setColor(COL_VALUE);
        Path d = new Path();
        float stemW = h * 0.22f;
        d.moveTo(left, top);
        d.lineTo(left + stemW, top);
        d.lineTo(left + stemW, top + h * 0.22f);
        d.lineTo(left + h * 0.52f, top + h * 0.22f);
        d.quadTo(left + h * 0.95f, top + h * 0.22f, left + h * 0.95f, top + h / 2f);
        d.quadTo(left + h * 0.95f, top + h * 0.78f, left + h * 0.52f, top + h * 0.78f);
        d.lineTo(left + stemW, top + h * 0.78f);
        d.lineTo(left + stemW, top + h);
        d.lineTo(left, top + h);
        d.close();
        Path hole = new Path();
        hole.moveTo(left + stemW * 2f, top + h * 0.40f);
        hole.lineTo(left + h * 0.50f, top + h * 0.40f);
        hole.quadTo(left + h * 0.72f, top + h * 0.40f, left + h * 0.72f, top + h / 2f);
        hole.quadTo(left + h * 0.72f, top + h * 0.60f, left + h * 0.50f, top + h * 0.60f);
        hole.lineTo(left + stemW * 2f, top + h * 0.60f);
        hole.close();
        d.op(hole, Path.Op.DIFFERENCE);
        canvas.drawPath(d, markPaint);
        markPaint.setColor(applyLineAlpha(cfgLineColor));
        canvas.drawCircle(left + h * 1.62f, top + h / 2f, h * 0.16f, markPaint);
    }

    /** panelColor(): argb(bgOpacity×255, surfaceRgb) —— DroidDeck 用主题 surface 色而非纯黑 */
    private int bgColor() {
        int a = Math.round(cfgBgAlpha * 255f);
        return (a << 24) | (SURFACE_RGB & 0x00FFFFFF);
    }

    private void drawBackground(Canvas canvas) {
        int w = getWidth(), h = getHeight();
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
            bgBw = w; bgBh = h; bgLineW = lineW; bgLineColor = cfgLineColor; bgLineAtTop = lineAtTop;
            Canvas bc = new Canvas(bgBitmap);
            bgPaint.setColor(color);
            bc.drawRoundRect(0f, 0f, w, h, bgRadiusPx, bgRadiusPx, bgPaint);
            if (lineW > 0f) {
                bgPaint.setColor(applyLineAlpha(cfgLineColor));
                if (lineAtTop) bc.drawRect(bgRadiusPx, 0f, w - bgRadiusPx, lineW, bgPaint);
                else bc.drawRect(bgRadiusPx, h - lineW, w - bgRadiusPx, h, bgPaint);
            }
            if (old != null) old.recycle();
        }
        canvas.drawBitmap(bgBitmap, 0f, 0f, null);
    }

    private float lineThicknessPx() {
        if (cfgLineIntensity <= 0f) return 0f;
        float stroke = cfgLineIntensity * LINE_MAX_DP * getResources().getDisplayMetrics().density;
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
            graphBw = bw; graphBh = bh;
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
            for (int i = 0; i < n; i++) { float d2 = Math.abs(vals[i] - mean); if (d2 > dev) dev = d2; }
            graphPaint.setStrokeWidth(sp(GRAPH_STROKE_SP, density()));
            graphPaint.setColor(COL_GRAPH);
            graphPath.reset();
            float step = bw / (n - 1);
            float half = bh / 2f * GRAPH_AMPLITUDE;
            for (int i = 0; i < n; i++) {
                float gx = i * step;
                float gy = Math.max(0f, Math.min(bh, bh / 2f - (vals[i] - mean) / dev * half));
                if (i == 0) graphPath.moveTo(gx, gy); else graphPath.lineTo(gx, gy);
            }
            bc.drawPath(graphPath, graphPaint);
        }
        canvas.drawBitmap(graphBitmap, graphRect.left, graphRect.top, null);
    }

    private float density() { return getResources().getDisplayMetrics().scaledDensity; }
    private static float sp(float sp, float sd) { return sp * sd; }

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
        if (fps >= Constants.Config.FPS_INTEGER_THRESHOLD) return String.format(Locale.US, "%.0f", fps);
        return String.format(Locale.US, "%.1f", fps);
    }

    private String clockString(long now) {
        String key = String.valueOf(now / 60000L);
        if (clockText.isEmpty() || !key.equals(lastClockKey)) {
            lastClockKey = key;
            clockText = DateFormat.format(clock24h
                    ? Constants.Modules.CLOCK_PATTERN_24H : Constants.Modules.CLOCK_PATTERN_12H, now).toString();
        }
        return clockText;
    }

    /** 剩余续航估算：剩余电荷 ÷ 平滑放电电流 */
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
                    int uAh = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
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
