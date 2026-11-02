package com.fflivemic.audio;

/** Mixes aligned interleaved PCM16_LE buffers. Inputs must have equal byte lengths. */
public final class PcmAudioMixer {
    private PcmAudioMixer() {}

    public static int mix(byte[] mic, int micLength, byte[] game, int gameLength,
                          byte[] out, float micGain, float gameGain) {
        if (mic == null || game == null || out == null) throw new IllegalArgumentException("Null PCM buffer");
        if (micLength < 0 || gameLength < 0 || micLength > mic.length || gameLength > game.length)
            throw new IllegalArgumentException("Invalid PCM length");
        int bytes = Math.min(Math.min(micLength, gameLength), out.length) & ~1;
        float mg = gain(micGain), gg = gain(gameGain);
        for (int i = 0; i < bytes; i += 2) {
            int m = (short)((mic[i] & 255) | (mic[i + 1] << 8));
            int g = (short)((game[i] & 255) | (game[i + 1] << 8));
            int v = Math.round(m * mg + g * gg);
            v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
            out[i] = (byte)v;
            out[i + 1] = (byte)(v >> 8);
        }
        return bytes;
    }
    private static float gain(float v) {
        if (Float.isNaN(v) || Float.isInfinite(v)) return 1f;
        return Math.max(0f, Math.min(2f, v));
    }
}
