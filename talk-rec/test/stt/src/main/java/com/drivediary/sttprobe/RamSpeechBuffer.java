package com.drivediary.sttprobe;

import java.util.Arrays;
import java.util.function.BiConsumer;

/** Bounded 16 kHz mono PCM buffer. The consumer must copy any samples it needs to retain. */
final class RamSpeechBuffer {
    static final int FRAME_BYTES = 640;
    private static final int PRE_ROLL_FRAMES = 15;
    private static final int MAX_FRAMES = 600;
    private static final int END_SILENCE_FRAMES = 50;
    private static final int MIN_VOICE_FRAMES = 10;

    private final byte[] preRoll = new byte[PRE_ROLL_FRAMES * FRAME_BYTES];
    private final byte[] utterance = new byte[MAX_FRAMES * FRAME_BYTES];
    private final BiConsumer<byte[], Integer> completed;
    private int nextFrame;
    private int preRollFrames;
    private int frames;
    private int onset;
    private int voiceFrames;
    private int silentFrames;
    private double noiseRms = 30;

    RamSpeechBuffer(BiConsumer<byte[], Integer> completed) {
        this.completed = completed;
    }

    void accept(byte[] frame) {
        if (frame.length != FRAME_BYTES) throw new IllegalArgumentException("Expected 20 ms of PCM");
        long squares = 0;
        for (int i = 0; i < frame.length; i += 2) {
            int sample = (short) ((frame[i] & 255) | (frame[i + 1] << 8));
            squares += (long) sample * sample;
        }
        double rms = Math.sqrt(squares / (frame.length / 2.0));
        boolean voice = rms > Math.max(140, 3.2 * noiseRms);
        if (frames == 0) {
            System.arraycopy(frame, 0, preRoll, nextFrame * FRAME_BYTES, FRAME_BYTES);
            nextFrame = (nextFrame + 1) % PRE_ROLL_FRAMES;
            preRollFrames = Math.min(PRE_ROLL_FRAMES, preRollFrames + 1);
            onset = voice ? onset + 1 : 0;
            if (!voice) noiseRms += 0.05 * (rms - noiseRms);
            if (onset == 4) {
                int oldest = (nextFrame + PRE_ROLL_FRAMES - preRollFrames) % PRE_ROLL_FRAMES;
                for (int i = 0; i < preRollFrames; i++) {
                    System.arraycopy(preRoll, ((oldest + i) % PRE_ROLL_FRAMES) * FRAME_BYTES,
                            utterance, i * FRAME_BYTES, FRAME_BYTES);
                }
                frames = preRollFrames;
                voiceFrames = onset;
            }
            return;
        }

        System.arraycopy(frame, 0, utterance, frames++ * FRAME_BYTES, FRAME_BYTES);
        if (voice) voiceFrames++;
        silentFrames = voice ? 0 : silentFrames + 1;
        if (silentFrames == END_SILENCE_FRAMES || frames == MAX_FRAMES) {
            try {
                if (voiceFrames >= MIN_VOICE_FRAMES) completed.accept(utterance, frames * FRAME_BYTES);
            } finally {
                reset();
            }
        }
    }

    // Drop partial audio on mic silencing, a route change, or opt-out.
    void reset() {
        Arrays.fill(preRoll, (byte) 0);
        Arrays.fill(utterance, (byte) 0);
        nextFrame = preRollFrames = frames = onset = voiceFrames = silentFrames = 0;
        noiseRms = 30;
    }
}
