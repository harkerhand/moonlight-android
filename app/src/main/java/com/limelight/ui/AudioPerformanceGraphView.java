package com.limelight.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.Arrays;
import java.util.Locale;

public class AudioPerformanceGraphView extends View {
    private static final int SAMPLE_COUNT = 60;
    private static final float MINIMUM_Y_MAX_MS = 50.0f;
    private static final int GRID_LINE_COUNT = 4;

    private final float[] rttSamples = new float[SAMPLE_COUNT];
    private final float[] audioQueueSamples = new float[SAMPLE_COUNT];
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final float density;

    private int nextSampleIndex;
    private int populatedSampleCount;

    public AudioPerformanceGraphView(Context context) {
        this(context, null);
    }

    public AudioPerformanceGraphView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AudioPerformanceGraphView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        gridPaint.setColor(0x33FFFFFF);
        gridPaint.setStrokeWidth(density);
        labelPaint.setColor(0x99FFFFFF);
        labelPaint.setTextSize(10.0f * getResources().getDisplayMetrics().scaledDensity);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.0f * density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        clear();
    }

    public void addSample(float rttMs, float audioQueueMs) {
        rttSamples[nextSampleIndex] = rttMs;
        audioQueueSamples[nextSampleIndex] = audioQueueMs;
        nextSampleIndex = (nextSampleIndex + 1) % SAMPLE_COUNT;
        populatedSampleCount = Math.min(populatedSampleCount + 1, SAMPLE_COUNT);
        invalidate();
    }

    public void clear() {
        Arrays.fill(rttSamples, Float.NaN);
        Arrays.fill(audioQueueSamples, Float.NaN);
        nextSampleIndex = 0;
        populatedSampleCount = 0;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float left = getPaddingLeft() + 4.0f * density;
        float top = getPaddingTop() + labelPaint.getTextSize();
        float right = getWidth() - getPaddingRight() - 4.0f * density;
        float bottom = getHeight() - getPaddingBottom() - labelPaint.getTextSize() * 0.5f;
        if (right <= left || bottom <= top) {
            return;
        }

        float yMaximum = calculateYMaximum();
        for (int i = 0; i <= GRID_LINE_COUNT; i++) {
            float y = top + (bottom - top) * i / GRID_LINE_COUNT;
            canvas.drawLine(left, y, right, y, gridPaint);
        }
        for (int i = 0; i <= 3; i++) {
            float x = left + (right - left) * i / 3;
            canvas.drawLine(x, top, x, bottom, gridPaint);
        }

        canvas.drawText(String.format(Locale.getDefault(), "%.0f ms", yMaximum),
                left, top - 2.0f * density, labelPaint);
        canvas.drawText("0", left, bottom + labelPaint.getTextSize(), labelPaint);

        drawSeries(canvas, rttSamples, Color.rgb(66, 165, 245), yMaximum,
                left, top, right, bottom);
        drawSeries(canvas, audioQueueSamples, Color.rgb(255, 179, 0), yMaximum,
                left, top, right, bottom);
    }

    private float calculateYMaximum() {
        float maximum = MINIMUM_Y_MAX_MS;
        for (int i = 0; i < populatedSampleCount; i++) {
            int sampleIndex = getSampleIndex(i);
            maximum = maximumFinite(maximum, rttSamples[sampleIndex]);
            maximum = maximumFinite(maximum, audioQueueSamples[sampleIndex]);
        }
        return (float) Math.ceil(maximum * 1.1f / 10.0f) * 10.0f;
    }

    private float maximumFinite(float currentMaximum, float value) {
        return Float.isNaN(value) ? currentMaximum : Math.max(currentMaximum, value);
    }

    private void drawSeries(Canvas canvas, float[] samples, int color, float yMaximum,
                            float left, float top, float right, float bottom) {
        linePath.reset();
        boolean drawingSegment = false;
        int firstSlot = SAMPLE_COUNT - populatedSampleCount;
        for (int i = 0; i < populatedSampleCount; i++) {
            float value = samples[getSampleIndex(i)];
            if (Float.isNaN(value)) {
                drawingSegment = false;
                continue;
            }

            int slot = firstSlot + i;
            float x = left + (right - left) * slot / (SAMPLE_COUNT - 1);
            float clippedValue = Math.max(0.0f, Math.min(value, yMaximum));
            float y = bottom - (bottom - top) * clippedValue / yMaximum;
            if (drawingSegment) {
                linePath.lineTo(x, y);
            }
            else {
                linePath.moveTo(x, y);
                drawingSegment = true;
            }
        }

        linePaint.setColor(color);
        canvas.drawPath(linePath, linePaint);
    }

    private int getSampleIndex(int chronologicalIndex) {
        int oldestSampleIndex = (nextSampleIndex - populatedSampleCount + SAMPLE_COUNT) % SAMPLE_COUNT;
        return (oldestSampleIndex + chronologicalIndex) % SAMPLE_COUNT;
    }
}
