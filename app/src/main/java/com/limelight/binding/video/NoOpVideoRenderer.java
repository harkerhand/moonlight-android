package com.limelight.binding.video;

import com.limelight.nvstream.av.video.VideoDecoderRenderer;
import com.limelight.nvstream.jni.MoonBridge;

public class NoOpVideoRenderer extends VideoDecoderRenderer {
    @Override
    public int setup(int format, int width, int height, int redrawRate) {
        return MoonBridge.DR_OK;
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                int frameNumber, int frameType, char frameHostProcessingLatency,
                                long receiveTimeUs, long enqueueTimeUs) {
        return MoonBridge.DR_OK;
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
