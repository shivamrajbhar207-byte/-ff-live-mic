package com.fflivemic.audio;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import java.io.IOException;
import java.nio.ByteBuffer;

/** AAC-LC encoder. Output callback is encoded elementary AAC, not RTMP/MP4. */
public final class AacAudioEncoder {
    public interface Listener {
        void onAacData(byte[] data, long ptsUs, int flags);
        void onFormatChanged(MediaFormat format);
        void onError(Exception error);
    }
    private final int rate, channels, bitrate;
    private final Listener listener;
    private MediaCodec codec;
    private boolean started;
    private long submittedFrames;

    public AacAudioEncoder(int sampleRate, int channelCount, int bitRate, Listener listener) {
        if (sampleRate <= 0 || (channelCount != 1 && channelCount != 2) || bitRate <= 0 || listener == null)
            throw new IllegalArgumentException("Invalid AAC configuration");
        rate = sampleRate; channels = channelCount; bitrate = bitRate; this.listener = listener;
    }
    public void start() throws IOException {
        if (started) return;
        MediaFormat f = MediaFormat.createAudioFormat("audio/mp4a-latm", rate, channels);
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        codec = MediaCodec.createEncoderByType("audio/mp4a-latm");
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec.start(); started = true;
    }
    public void queuePcm(byte[] pcm, int length) {
        if (!started || codec == null) throw new IllegalStateException("AAC encoder not started");
        if (pcm == null || length < 0 || length > pcm.length || (length & 1) != 0) throw new IllegalArgumentException("Bad PCM length");
        try {
            int offset = 0;
            while (offset < length) {
                int ix = codec.dequeueInputBuffer(10000);
                if (ix < 0) { drain(); continue; }
                ByteBuffer in = codec.getInputBuffer(ix);
                if (in == null) { codec.queueInputBuffer(ix, 0, 0, 0, 0); continue; }
                in.clear(); int n = Math.min(in.remaining(), length - offset) & ~1;
                if (n == 0) { codec.queueInputBuffer(ix, 0, 0, 0, 0); break; }
                in.put(pcm, offset, n);
                long pts = submittedFrames * 1000000L / rate;
                submittedFrames += n / (2L * channels);
                codec.queueInputBuffer(ix, 0, n, pts, 0);
                offset += n; drain();
            }
        } catch (Exception e) { listener.onError(e); }
    }
    private void drain() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int ix = codec.dequeueOutputBuffer(info, 0);
            if (ix == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (ix == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { listener.onFormatChanged(codec.getOutputFormat()); continue; }
            if (ix < 0) continue;
            ByteBuffer b = codec.getOutputBuffer(ix);
            if (b != null && info.size > 0) {
                b.position(info.offset); b.limit(info.offset + info.size);
                byte[] data = new byte[info.size]; b.get(data);
                listener.onAacData(data, info.presentationTimeUs, info.flags);
            }
            codec.releaseOutputBuffer(ix, false);
        }
    }
    public void stop() {
        if (codec == null) return;
        try { if (started) codec.stop(); } catch (Exception e) { listener.onError(e); }
        try { codec.release(); } catch (Exception ignored) {}
        codec = null; started = false;
    }
}
