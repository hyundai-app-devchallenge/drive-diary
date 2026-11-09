package com.drivediary.sttprobe;

import android.content.Context;
import android.util.AtomicFile;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

final class StoryReplayProbe {
    private static final int LISTEN_MS = 6000;
    private static final int GAP_MS = 10000;
    private static final int CYCLE_MS = LISTEN_MS + GAP_MS;
    private static final int GLEO_START_MS = 18000;
    private static final int GLEO_END_MS = 21000;
    private static final int SIGNAL_LOST_MS = 64000;
    private static final int AUDIO_BYTES_PER_MS = SyntheticStreamProbe.BYTES_PER_SECOND / 1000;
    static final int EXPECTED_FORWARDED = 3;
    static final String CANDIDATES_FILE = "story-probe-candidates.txt";

    private static final class Clip {
        final String id;
        final String asset;
        final int startMs;

        Clip(String id, String asset, int startMs) {
            this.id = id;
            this.asset = asset;
            this.startMs = startMs;
        }
    }

    static byte[] timeline(Context context, Consumer<String> report) throws IOException {
        Clip[] clips = {
            new Clip("story", "feature2/wave-story.pcm", 1000),
            new Clip("story_in_10s_gap", "ko-KR.pcm", 8000),
            new Clip("story_overlapping_gleo", "ko-friends.pcm", 16400),
            new Clip("story_crossing_window_end", "ko-KR.pcm", 21100),
            new Clip("routine", "feature2/route-question.pcm", 32500),
            new Clip("family_story", "feature2/family-story.pcm", 48500),
            new Clip("story_with_unknown_assistant_state", "ko-KR.pcm", 64500)
        };
        byte[] timeline = new byte[70000 * AUDIO_BYTES_PER_MS];
        int forwarded = 0;
        for (Clip clip : clips) {
            byte[] pcm;
            try (InputStream input = context.getAssets().open(clip.asset)) {
                pcm = input.readAllBytes();
            }
            int endMs = clip.startMs + (pcm.length + AUDIO_BYTES_PER_MS - 1) / AUDIO_BYTES_PER_MS;
            String gate = gate(clip.startMs, endMs);
            report.accept("Replay gate: " + clip.id + " " + clip.startMs + "-" + endMs + "ms " + gate);
            if ("forward".equals(gate)) {
                System.arraycopy(pcm, 0, timeline, clip.startMs * AUDIO_BYTES_PER_MS, pcm.length);
                forwarded++;
            }
        }
        if (forwarded != EXPECTED_FORWARDED) throw new IllegalStateException("Unexpected gate fixture");
        return timeline;
    }

    private static String gate(int startMs, int endMs) {
        if (endMs > SIGNAL_LOST_MS) return "drop_unknown_gleo_state";
        if (startMs < GLEO_END_MS && GLEO_START_MS < endMs) return "drop_gleo_overlap";
        int windowEnd = startMs / CYCLE_MS * CYCLE_MS + LISTEN_MS;
        if (startMs >= windowEnd) return "drop_10s_gap";
        if (endMs > windowEnd) return "drop_incomplete_clip";
        return "forward";
    }

    static void evaluateModel(Context context, TinyStoryModel model, Consumer<String> report) throws IOException {
        int tp = 0, tn = 0, fp = 0, fn = 0;
        List<String> inputs = new ArrayList<>();
        try (BufferedReader reader = TinyStoryModel.reader(context, "feature2/story-holdout.tsv")) {
            for (String line; (line = reader.readLine()) != null;) {
                String[] fields = line.split("\t", 2);
                boolean expected = "1".equals(fields[0]);
                boolean predicted = model.score(fields[1]) >= TinyStoryModel.THRESHOLD;
                inputs.add(fields[1]);
                if (expected && predicted) tp++;
                else if (!expected && !predicted) tn++;
                else if (predicted) fp++;
                else fn++;
                if (expected != predicted) {
                    report.accept("Holdout mismatch expected=" + expected + " text=" + fields[1]);
                }
            }
        }
        for (int i = 0; i < 100; i++) model.score(inputs.get(i % inputs.size()));
        long[] times = new long[500];
        for (int i = 0; i < times.length; i++) {
            long start = System.nanoTime();
            model.score(inputs.get(i % inputs.size()));
            times[i] = System.nanoTime() - start;
        }
        Arrays.sort(times);
        int size;
        try (InputStream input = context.getAssets().open(TinyStoryModel.MODEL_ASSET)) {
            size = input.readAllBytes().length;
        }
        report.accept(String.format(Locale.ROOT,
                "Local story model bytes=%d n=%d TP=%d TN=%d FP=%d FN=%d p50=%.3fms p95=%.3fms",
                size, inputs.size(), tp, tn, fp, fn, times[249] / 1e6, times[474] / 1e6));
    }

    static void saveCandidates(Context context, TinyStoryModel model, List<String> finals,
            Consumer<String> report) throws IOException {
        List<String> kept = new ArrayList<>();
        for (String line : finals) {
            String text = line.substring(line.indexOf('\t') + 1);
            double score = model.score(text);
            boolean keep = score >= TinyStoryModel.THRESHOLD;
            report.accept(String.format(Locale.ROOT, "Replay classification score=%.3f %s %s",
                    score, keep ? "keep" : "drop", text));
            if (keep) kept.add(text);
        }
        AtomicFile file = new AtomicFile(new File(context.getCacheDir(), CANDIDATES_FILE));
        FileOutputStream output = file.startWrite();
        try {
            output.write(String.join("\n", kept).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException error) {
            file.failWrite(output);
            throw error;
        }
        report.accept("Story candidates saved: " + kept.size());
    }

    private StoryReplayProbe() {}
}
