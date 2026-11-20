package com.stayclean;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** A soft wheel "tick", synthesised once (about 20 ms of audio, no sound file). Follows the phone's silent mode. */
final class TickSound {
    private AudioTrack track;
    private final short[] pcm;

    TickSound() {
        int sr = 22050, n = (int) (sr * 0.022);
        pcm = new short[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) sr, env = Math.exp(-t / 0.0045) * Math.min(1.0, i / 24.0);
            double s = Math.sin(2 * Math.PI * 2100 * t) * 0.6 + Math.sin(2 * Math.PI * 1050 * t) * 0.4;
            pcm[i] = (short) (s * env * 0.42 * Short.MAX_VALUE);
        }
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(n * 2).setTransferMode(AudioTrack.MODE_STATIC).build();
            track.write(pcm, 0, n);
        } catch (Throwable t) { track = null; }
    }

    void play() {
        AudioTrack t = track;
        if (t == null) return;
        try { t.stop(); t.reloadStaticData(); t.play(); } catch (Throwable ignored) {}
    }

    void release() {
        AudioTrack t = track; track = null;
        if (t != null) { try { t.release(); } catch (Throwable ignored) {} }
    }
}
