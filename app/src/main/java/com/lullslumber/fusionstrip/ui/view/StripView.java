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
 * Strip — 一条撑满屏幕宽的单行性能横条（M2）。
 *
 * 布局移植自 DroidDeck 的 Strip（PR #1935，GPL-3.0，经 Bannerlator 转手）：
 * 从左到右依次是 FPS + 帧时间、帧率历史图、1% low、GPU、CPU、RAM、BAT，
 * 时钟钉在最右端；放不下的指标从尾部（优先级最低处）开始丢弃。
 *
 * 与 Bannerlator 版的三处数据源差异（原生安卓 vs Winlator 容器内）：
 * - 没有 API 标签（DXVK/Vulkan 那一格），用 "FPS" 字样顶位；
 * - 没有 VRAM 指标，对应项省略；
 * - 帧图的数据源是 1Hz 的 FPS 采样（TimeStats 差分），不是逐帧时间戳，
 *   所以画的是「FPS 历史」而不是真·帧时间曲线——这是数据源决定的硬上限。
 *
 * 渲染优化移植自 DroidDeck PR #2235（hybrid 方案）：
 * - 帧图画进一张可复用的 ARGB bitmap（尺寸变了才重建，每次先 eraseColor
 *   擦旧轨迹再画，防残影），onDraw 里只 drawBitmap；
 * - 背景按（宽、高）缓存成 bitmap，key 不变就整帧复用；
 * - graph 重绘限频 66ms 一次（STRIP_GRAPH_REDRAW_MS）。本 view 的数据是
 *   1Hz 采集回调，天然远低于该阈值，这条限频是防御性兜底
 *   （采集周期被调到很低时也不会把 UI 线程打爆）。
 *
 * 跑在 SystemUI 进程里，由 ModuleMain 在 Strip 模式下注入独立悬浮窗
 * （窗口宽 MATCH_PARENT、高 WRAP_CONTENT）。采集线程随 attach/detach 启停，
 * 隐藏状态下零开销。
 */
public class StripView extends View {

    // ---- 配色：彩色标签 + 白色数值，对齐 Strip 的视觉语言 ----
    private static final int COL_VALUE = 0xFFFFFFFF;
    private static final int COL_DIM = 0xB3FFFFFF;
    private static final int COL_FPS = 0xFF7FD4FF;
    private static final int COL_LO = 0xFFB0BEC5;
    private static final int COL_GPU = 0xFFFFB74D;
    private static final int COL_CPU = 0xFF64B5F6;
    private static final int COL_RAM = 0xFF81C784;
    private static final int COL_BAT = 0xFFFF8A80;
    private static final int COL_GRAPH = 0xFF8AB4F8;
    /** 背景半透明黑（对齐 Bannerlator 的 bgOpacity≈0.63） */
    private static final int BG_COLOR = 0xA1000000;
    /** 背景圆角（sp，按字号缩放会失真，固定 sp 值即可） */
    private static final float BG_RADIUS_SP = 8f;

    // ---- 布局常量（sp），数值照搬 Bannerlator buildStrip() ----
    private static final float BASE_SP = 12f;
    private static final float UNIT_SCALE = 0.85f;
    private static final float NUM_GAP_SCALE = 0.28f;
    private static final float PAD_SP = 7f;
    private static final float ITEM_GAP_SP = 10f;
    private static final float END_PAD_SP = 16f;
    private static final float START_PAD_SP = 34f;
    private static final float GRAPH_W_SP = 56f;
    private static final float GRAPH_STROKE_SP = 1.6f;

    /** graph 重绘最小间隔（ms），#2235 三件套之一 */
    private static final long STRIP_GRAPH_REDRAW_MS = 66L;
    /** FPS 历史样本容量：60 个 1Hz 样本 = 60 秒窗口 */
    private static final int HIST_CAP = 60;

    /** 一段要画的文字：内容、颜色、字号（px）、画完之后跟多宽的间隙 */
    private static final class Span {
        final String text;
        final int color;
        final float sizePx;
        final float gapAfter;

        Span(String text, int color, float sizePx, float gapAfter) {
            this.text = text;
            this.color = color;
            this.sizePx = sizePx;
            this.gapAfter = gapAfter;
        }
    }

    /** 一个优先级 piece：整段宽度 + 摆下时要画的 span 列表（x 待定）；spans 为 null 表示帧图 */
    private static final class Piece {
        final float width;
        final List<Span> spans;

        Piece(float width, List<Span> spans) {
            this.width = width;
            this.spans = spans;
        }
    }

    /** 摆好位置之后的文字：绝对 x、基线 y、颜色、字号 */
    private static final class Glyph {
        final String text;
        final float x;
        final float baseline;
        final int color;
        final float sizePx;

        Glyph(String text, float x, float baseline, int color, float sizePx) {
            this.text = text;
            this.x = x;
            this.baseline = baseline;
            this.color = color;
            this.sizePx = sizePx;
        }
    }

    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint graphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path graphPath = new Path();

    /** 摆好的文字（每次数据更新重建） */
    private final List<Glyph> glyphs = new ArrayList<>();
    /** 帧图在 view 里的位置，null = 这帧没摆下 */
    private RectF graphRect;
    /** 背景圆角（px），rebuild 时换算一次 */
    private float bgRadiusPx;
    /** 单行内容总高（px） */
    private float contentH;

    /** FPS 历史（环形缓冲），同时喂帧图和 1% low */
    private final float[] fpsHistory = new float[HIST_CAP];
    private int histCount;
    private int histHead;
    /** 最近一拍 FPS，用于文本和帧时间换算 */
    private float lastFps = -1f;

    private String clockText = "";
    private String lastClockKey = "";
    private boolean clock24h = true;

    // ---- #2235：graph bitmap 缓存 ----
    private Bitmap graphBitmap;
    private int graphBw;
    private int graphBh;
    /** 上次数据重建时刻，66ms 限频用 */
    private long lastRebuildAt;

    // ---- #2235：背景 bitmap 缓存 ----
    private Bitmap bgBitmap;
    private int bgBw;
    private int bgBh;

    private PerformanceCollector collector;

    public StripView(Context context) {
        super(context);
        clock24h = DateFormat.is24HourFormat(context);
        textPaint.setTypeface(resolveTypeface());
        graphPaint.setStyle(Paint.Style.STROKE);
        collector = new PerformanceCollector(context, this::updateData);
    }

    private static Typeface resolveTypeface() {
        Typeface t = MonitorTypeface.get(true);
        return t != null ? t : Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
    }

    // ---- 生命周期 ----

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (collector != null) {
            collector.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (collector != null) {
            collector.stop();
        }
    }

    /** 退化模式（挂在状态栏里）时由 MonitorOverlay 显式启停采集 */
    public void setCollecting(boolean collecting) {
        if (collector == null) {
            return;
        }
        if (collecting) {
            collector.start();
        } else {
            collector.stop();
        }
    }

    public void setClock24h(boolean use24h) {
        if (clock24h == use24h) {
            return;
        }
        clock24h = use24h;
        lastClockKey = "";
    }

    // ---- 数据入口 ----

    /**
     * PerformanceCollector 的回调（1Hz 或用户设定的周期）。
     * 记一拍 FPS 进历史，重建整条 Strip 的布局，然后 invalidate。
     */
    public void updateData(PerformanceCollector.PerformanceData data) {
        long now = System.currentTimeMillis();
        // 66ms 限频（#2235）：数据来太快就丢拍，保护 UI 线程
        if (now - lastRebuildAt < STRIP_GRAPH_REDRAW_MS) {
            return;
        }
        lastRebuildAt = now;

        pushFps(data.fps);
        FpsSyncBridge.publish(getContext(), data.fps, data.fpsTag);
        rebuild(density(), now);
        invalidate();
    }

    private void pushFps(float fps) {
        if (fps > 0f) {
            lastFps = fps;
        }
        // 读不到帧率记 0：图上留个空洞，别把后面的样本往前拽
        fpsHistory[histHead] = Math.max(fps, 0f);
        histHead = (histHead + 1) % HIST_CAP;
        if (histCount < HIST_CAP) {
            histCount++;
        }
    }

    /** 按时间顺序取历史样本（旧→新） */
    private void collectHistory(float[] out) {
        int start = (histHead - histCount + HIST_CAP) % HIST_CAP;
        for (int i = 0; i < histCount; i++) {
            out[i] = fpsHistory[(start + i) % HIST_CAP];
        }
    }

    /**
     * 1% low：历史样本里最差的 1% 的均值。
     * TimeStats 数据源没有逐帧时间戳，这是秒级分辨率下的近似——
     * 和 Bannerlator 用逐帧算出来的真 1% low 相比对瞬时卡顿不敏感，够看但别较真。
     */
    private float computeLow1() {
        if (histCount == 0) {
            return -1f;
        }
        float[] vals = new float[histCount];
        collectHistory(vals);
        Arrays.sort(vals);
        int n = Math.max(1, (int) Math.ceil(vals.length * 0.01f));
        float sum = 0f;
        int used = 0;
        for (int i = 0; i < n; i++) {
            if (vals[i] > 0f) {
                sum += vals[i];
                used++;
            }
        }
        return used > 0 ? sum / used : -1f;
    }

    // ---- 布局：把 pieces 依次摆进可用宽度 ----

    private void rebuild(float density, long now) {
        float px = sp(BASE_SP, density);
        float unitPx = px * UNIT_SCALE;
        float numGap = px * NUM_GAP_SCALE;
        float pad = sp(PAD_SP, density);
        float itemGap = sp(ITEM_GAP_SP, density);
        float endPad = sp(END_PAD_SP, density);
        float startPad = sp(START_PAD_SP, density);
        bgRadiusPx = sp(BG_RADIUS_SP, density);

        textPaint.setTextSize(px);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float line = fm.descent - fm.ascent;
        float baseline = pad - fm.ascent;
        float viewW = getWidth() > 0 ? getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        float viewH = pad * 2f + line;
        contentH = viewH;

        // 时钟钉在最右端，先算出它的宽度和可用右边界
        String clock = clockString(now);
        float clockW = measure(clock, px);
        float rightLimit = viewW - endPad - (clockW > 0f ? clockW + itemGap : 0f);

        List<Piece> pieces = new ArrayList<>();

        // FPS + 帧时间（原生没有 API 标签，用 FPS 字样顶位）
        String fpsText = formatFps(lastFps);
        String msText = lastFps > 0f
                ? String.format(Locale.US, "%.1f", 1000f / Math.max(lastFps, 1f))
                : "--";
        List<Span> fpsPiece = new ArrayList<>();
        fpsPiece.add(new Span("FPS", COL_FPS, unitPx, unitPx * 0.5f));
        fpsPiece.add(new Span(fpsText, COL_VALUE, px, numGap));
        fpsPiece.add(new Span("fps", COL_DIM, unitPx, unitPx * 0.5f));
        fpsPiece.add(new Span(msText, COL_VALUE, px, numGap));
        fpsPiece.add(new Span("ms", COL_DIM, unitPx, 0f));
        pieces.add(new Piece(runWidth(fpsPiece), fpsPiece));

        // 帧率历史图（56sp 宽，线高 0.9×行高）；spans 为 null 是帧图 marker
        float graphW = sp(GRAPH_W_SP, density);
        float graphH = line * 0.9f;
        pieces.add(new Piece(graphW, null));

        // 1% low
        float low1 = computeLow1();
        List<Span> lowPiece = new ArrayList<>();
        lowPiece.add(new Span("1%", COL_LO, unitPx, unitPx * 0.5f));
        lowPiece.add(new Span(low1 > 0f ? String.format(Locale.US, "%.0f", low1) : "--",
                COL_VALUE, px, 0f));
        pieces.add(new Piece(runWidth(lowPiece), lowPiece));

        // GPU / CPU / RAM（原生没有 VRAM，省略）
        addMetricPiece(pieces, "GPU", COL_GPU,
                String.format(Locale.US, "%d", d.gpuTotalUsage), "%", px, unitPx, numGap);
        addMetricPiece(pieces, "CPU", COL_CPU,
                String.format(Locale.US, "%d", (int) d.cpuTotalUsage), "%", px, unitPx, numGap);
        addMetricPiece(pieces, "RAM", COL_RAM,
                String.format(Locale.US, "%d", (int) d.ramUsagePercent), "%", px, unitPx, numGap);

        // BAT：温度 + 功率（有什么画什么）。PerformanceData 暂无电量百分比字段，
        // 留到 M3（WebUI 调参台）连 collector 一起补
        List<Span> bat = new ArrayList<>();
        bat.add(new Span("BAT", COL_BAT, unitPx, unitPx * 0.5f));
        boolean any = false;
        if (!Float.isNaN(d.batteryTemp) && d.batteryTemp > 0f) {
            bat.add(new Span(String.format(Locale.US, "%.0f°", d.batteryTemp),
                    COL_VALUE, px, unitPx * 0.5f));
            any = true;
        }
        if (d.batteryPowerW != 0f) {
            bat.add(new Span(String.format(Locale.US, "%+.1f", d.batteryPowerW),
                    COL_VALUE, px, numGap));
            bat.add(new Span("W", COL_DIM, unitPx, 0f));
            any = true;
        }
        if (any) {
            pieces.add(new Piece(runWidth(bat), bat));
        }

        // 依次摆放：放不下的从尾部开始丢（优先级最低的先出局）
        glyphs.clear();
        graphRect = null;
        float x = startPad;
        for (Piece p : pieces) {
            float nextGap = x > startPad ? itemGap : 0f;
            if (x + nextGap + p.width > rightLimit) {
                break;
            }
            x += nextGap;
            if (p.spans != null) {
                for (Span s : p.spans) {
                    glyphs.add(new Glyph(s.text, x, baseline, s.color, s.sizePx));
                    x += measure(s.text, s.sizePx) + s.gapAfter;
                }
            } else {
                graphRect = new RectF(x, (viewH - graphH) / 2f, x + graphW,
                        (viewH + graphH) / 2f);
                x += p.width;
            }
        }
        if (clockW > 0f) {
            glyphs.add(new Glyph(clock, viewW - endPad - clockW, baseline, COL_DIM, px));
        }

        if (getMeasuredHeight() != Math.round(viewH)) {
            requestLayout();
        }
    }

    private void addMetricPiece(List<Piece> pieces, String label, int labelColor,
                                String value, String unit, float px, float unitPx, float numGap) {
        List<Span> l = new ArrayList<>();
        l.add(new Span(label, labelColor, unitPx, unitPx * 0.5f));
        l.add(new Span(value, COL_VALUE, px, numGap));
        l.add(new Span(unit, COL_DIM, unitPx, 0f));
        pieces.add(new Piece(runWidth(l), l));
    }

    // ---- 测量 / 绘制 ----

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float density = density();
        float pad = sp(PAD_SP, density);
        textPaint.setTextSize(sp(BASE_SP, density));
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
            canvas.drawText(g.text, g.x, g.baseline, textPaint);
        }
        drawGraphCached(canvas);
    }

    /** 背景（#2235：按宽高缓存成 bitmap，key 不变整帧复用） */
    private void drawBackground(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        if (bgBitmap == null || bgBw != w || bgBh != h) {
            Bitmap old = bgBitmap;
            bgBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bgBw = w;
            bgBh = h;
            Canvas bc = new Canvas(bgBitmap);
            bgPaint.setColor(BG_COLOR);
            bc.drawRoundRect(0f, 0f, w, h, bgRadiusPx, bgRadiusPx, bgPaint);
            if (old != null) {
                old.recycle();
            }
        }
        canvas.drawBitmap(bgBitmap, 0f, 0f, null);
    }

    /** 帧率历史图（#2235：画进可复用 bitmap，先擦旧轨迹再画，防残影） */
    private void drawGraphCached(Canvas canvas) {
        if (graphRect == null) {
            return;
        }
        int bw = Math.max(1, Math.round(graphRect.width()));
        int bh = Math.max(1, Math.round(graphRect.height()));
        if (graphBitmap == null || graphBw != bw || graphBh != bh) {
            Bitmap old = graphBitmap;
            graphBitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            graphBw = bw;
            graphBh = bh;
            if (old != null) {
                old.recycle();
            }
        }
        // 先擦除再画：上一帧的轨迹留在缓存里会叠成残影（#2235 修的就是这个）
        graphBitmap.eraseColor(Color.TRANSPARENT);
        Canvas bc = new Canvas(graphBitmap);

        float[] vals = new float[histCount];
        collectHistory(vals);
        int n = 0;
        float peak = 1f;
        for (int i = 0; i < histCount; i++) {
            if (vals[i] > 0f) {
                vals[n++] = vals[i];
                if (vals[i] > peak) {
                    peak = vals[i];
                }
            }
        }
        if (n >= 2) {
            graphPaint.setStrokeWidth(sp(GRAPH_STROKE_SP, density()));
            graphPaint.setColor(COL_GRAPH);
            graphPath.reset();
            float step = bw / (n - 1);
            for (int i = 0; i < n; i++) {
                float gx = i * step;
                float gy = bh - (vals[i] / peak) * bh;
                if (i == 0) {
                    graphPath.moveTo(gx, gy);
                } else {
                    graphPath.lineTo(gx, gy);
                }
            }
            bc.drawPath(graphPath, graphPaint);
        }
        canvas.drawBitmap(graphBitmap, graphRect.left, graphRect.top, null);
    }

    // ---- 工具 ----

    private float density() {
        return getResources().getDisplayMetrics().scaledDensity;
    }

    /** sp → px，跟 TextView.setTextSize(COMPLEX_UNIT_SP) 同基准 */
    private static float sp(float sp, float scaledDensity) {
        return sp * scaledDensity;
    }

    private float runWidth(List<Span> spans) {
        float w = 0f;
        for (Span s : spans) {
            w += measure(s.text, s.sizePx) + s.gapAfter;
        }
        return w;
    }

    private float measure(String text, float sizePx) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        textPaint.setTextSize(sizePx);
        return textPaint.measureText(text);
    }

    private String formatFps(float fps) {
        if (fps < 0f) {
            return "--";
        }
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
}
