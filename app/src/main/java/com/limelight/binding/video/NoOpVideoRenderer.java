package com.limelight.binding.video;

import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.jni.MoonBridge;

public class NoOpVideoRenderer extends VideoDecoderRenderer {
    public static final class PerformanceSnapshot {
        public final float receivedFps;
        public final float frameLossPercentage;

        private PerformanceSnapshot(float receivedFps, float frameLossPercentage) {
            this.receivedFps = receivedFps;
            this.frameLossPercentage = frameLossPercentage;
        }
    }

    private final boolean enablePerformanceMetrics;
    private long measurementStartNs;
    private boolean hasLastFrameNumber;
    private int lastFrameNumber;
    private long framesReceived;
    private long framesLost;

    public NoOpVideoRenderer() {
        this(false);
    }

    public NoOpVideoRenderer(boolean enablePerformanceMetrics) {
        this.enablePerformanceMetrics = enablePerformanceMetrics;
        if (enablePerformanceMetrics) {
            measurementStartNs = System.nanoTime();
        }
    }

    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        return MoonBridge.DR_OK;
    }

    @Override
    public synchronized void start() {
        if (enablePerformanceMetrics) {
            measurementStartNs = System.nanoTime();
            hasLastFrameNumber = false;
            framesReceived = 0;
            framesLost = 0;
        }
    }

    @Override
    public void stop() {
    }

    @Override
    public int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                int frameNumber, int frameType, char frameHostProcessingLatency,
                                long receiveTimeUs, long enqueueTimeUs) {
        if (enablePerformanceMetrics && decodeUnitType == MoonBridge.BUFFER_TYPE_PICDATA) {
            recordFrame(frameNumber);
        }
        return MoonBridge.DR_OK;
    }

    private synchronized void recordFrame(int frameNumber) {
        if (hasLastFrameNumber) {
            long frameDelta = (frameNumber - lastFrameNumber) & 0xFFFFFFFFL;
            if (frameDelta == 0) {
                return;
            }

            // A delta in the upper half of the sequence space is an old/out-of-order frame.
            if (frameDelta >= 0x80000000L) {
                return;
            }
            framesLost += frameDelta - 1;
        }

        hasLastFrameNumber = true;
        lastFrameNumber = frameNumber;
        framesReceived++;
    }

    public synchronized PerformanceSnapshot getPerformanceSnapshot() {
        if (!enablePerformanceMetrics) {
            return null;
        }

        long nowNs = System.nanoTime();
        long elapsedNs = nowNs - measurementStartNs;
        float receivedFps = framesReceived > 0 && elapsedNs > 0 ?
                (float) (framesReceived * 1_000_000_000.0 / elapsedNs) : Float.NaN;
        long totalFrames = framesReceived + framesLost;
        float frameLossPercentage = totalFrames > 0 ?
                (float) framesLost * 100.0f / totalFrames : Float.NaN;
        measurementStartNs = nowNs;
        framesReceived = 0;
        framesLost = 0;

        return new PerformanceSnapshot(receivedFps, frameLossPercentage);
    }

    @Override
    public void cleanup() {
    }

    @Override
    public int getCapabilities() {
        return MoonBridge.CAPABILITY_DIRECT_SUBMIT;
    }

    @Override
    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
    }
}
