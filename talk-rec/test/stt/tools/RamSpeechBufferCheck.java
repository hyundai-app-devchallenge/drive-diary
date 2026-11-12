package com.drivediary.sttprobe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Run with javac/java independently of the Android SDK. */
public final class RamSpeechBufferCheck {
    public static void main(String[] args) {
        List<Integer> sizes = new ArrayList<>();
        byte[][] borrowed = new byte[1][];
        RamSpeechBuffer buffer = new RamSpeechBuffer((pcm, size) -> {
            sizes.add(size);
            borrowed[0] = pcm;
        });
        byte[] silence = new byte[RamSpeechBuffer.FRAME_BYTES];
        byte[] speech = new byte[silence.length];
        Arrays.fill(speech, (byte) 16);

        feed(buffer, silence, 60);
        feed(buffer, speech, 30);
        buffer.reset(); // Simulated silencing/route change before the utterance ended.
        feed(buffer, silence, 60);
        check(sizes.isEmpty(), "Silence and interrupted speech must not emit a segment");
        feed(buffer, speech, 30);
        feed(buffer, silence, 49);
        check(sizes.isEmpty(), "End silence must be applied");
        feed(buffer, silence, 1);
        check(sizes.size() == 1 && sizes.get(0) > 30 * speech.length, "Expected speech plus context");
        check(Arrays.equals(borrowed[0], new byte[borrowed[0].length]), "Borrowed PCM must be cleared");
        feed(buffer, speech, 600);
        check(sizes.size() == 2 && sizes.get(1) == 384000, "Unbroken speech must respect the 12s cap");
        System.out.println("RAM segmenter passed silence, route reset, endpoint, zeroing, and size cap");
    }

    private static void feed(RamSpeechBuffer buffer, byte[] frame, int count) {
        for (int i = 0; i < count; i++) buffer.accept(frame);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
