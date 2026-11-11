package com.drivediary.sttprobe;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.os.Debug;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EncoderMemoryProbe {
    private static final String TAG = "StoryEncoderBench";
    private static final class Sample {
        final boolean label;
        final double expected;
        final long[] ids;

        Sample(boolean label, double expected, long[] ids) {
            this.label = label;
            this.expected = expected;
            this.ids = ids;
        }
    }

    private EncoderMemoryProbe() {}

    public static void start(Context context) {
        new Thread(() -> {
            try {
                run(context.getFilesDir().toPath());
            } catch (Exception error) {
                Log.e(TAG, "Encoder probe failed", error);
            }
        }, "story-encoder-bench").start();
    }

    private static void run(Path directory) throws Exception {
        List<String> fixture = Files.readAllLines(directory.resolve("encoder-bench.tsv"));
        String[] settings = fixture.get(0).split("\t");
        double bias = Double.parseDouble(settings[0]);
        double threshold = Double.parseDouble(settings[1]);
        double[] weights = Arrays.stream(fixture.get(1).split(",")).mapToDouble(Double::parseDouble).toArray();
        List<Sample> samples = new ArrayList<>();
        for (String line : fixture.subList(2, fixture.size())) {
            String[] columns = line.split("\t");
            samples.add(new Sample("1".equals(columns[1]), Double.parseDouble(columns[2]),
                    Arrays.stream(columns[3].split(",")).mapToLong(Long::parseLong).toArray()));
        }
        Log.i(TAG, "idlePssKiB=" + Debug.getPss());
        OrtEnvironment environment = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            long start = SystemClock.elapsedRealtime();
            long loadCpuStart = Process.getElapsedCpuTime();
            try (OrtSession session = environment.createSession(directory.resolve("encoder.onnx").toString(), options)) {
                Log.i(TAG, "loadedMs=" + (SystemClock.elapsedRealtime() - start)
                        + " loadCpuMs=" + (Process.getElapsedCpuTime() - loadCpuStart)
                        + " loadedPssKiB=" + Debug.getPss());
                for (int i = 0; i < 8; i++) score(environment, session, samples.get(i), weights, bias);
                int[] confusion = new int[4];
                double maxDiff = 0;
                long[] times = new long[samples.size() * 4];
                for (int i = 0; i < times.length; i++) {
                    Sample sample = samples.get(i % samples.size());
                    long tick = System.nanoTime();
                    double result = score(environment, session, sample, weights, bias);
                    times[i] = System.nanoTime() - tick;
                    maxDiff = Math.max(maxDiff, Math.abs(result - sample.expected));
                    if (i < samples.size()) {
                        boolean keep = result >= threshold;
                        confusion[keep ? (sample.label ? 0 : 1) : (sample.label ? 2 : 3)]++;
                    }
                }
                Arrays.sort(times);
                Log.i(TAG, String.format(Locale.ROOT,
                        "pretokenized n=%d p50=%.2fms p95=%.2fms warmedPssKiB=%d diff=%.6f TP/FP/FN/TN=%s",
                        times.length, times[times.length / 2] / 1e6,
                        times[(int) Math.ceil(times.length * 0.95) - 1] / 1e6,
                        Debug.getPss(), maxDiff, Arrays.toString(confusion)));
                long wall = SystemClock.elapsedRealtime();
                long cpu = Process.getElapsedCpuTime();
                for (int i = 0; i < 30; i++) {
                    score(environment, session, samples.get(i % samples.size()), weights, bias);
                    SystemClock.sleep(Math.max(0, wall + 1000L * (i + 1) - SystemClock.elapsedRealtime()));
                }
                Log.i(TAG, String.format(Locale.ROOT, "30s at 1 decision/s process single-core CPU=%.2f%%",
                        100.0 * (Process.getElapsedCpuTime() - cpu) / (SystemClock.elapsedRealtime() - wall)));
            }
        }
        Log.i(TAG, "releasedPssKiB=" + Debug.getPss());
    }

    private static double score(OrtEnvironment environment, OrtSession session, Sample sample,
            double[] weights, double bias) throws Exception {
        long[] mask = new long[sample.ids.length];
        Arrays.fill(mask, 1);
        long[] shape = {1, mask.length};
        try (OnnxTensor ids = OnnxTensor.createTensor(environment, LongBuffer.wrap(sample.ids), shape);
             OnnxTensor attention = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape);
             OnnxTensor types = OnnxTensor.createTensor(environment, LongBuffer.wrap(new long[mask.length]), shape);
             OrtSession.Result result = session.run(Map.of(
                     "input_ids", ids, "attention_mask", attention, "token_type_ids", types))) {
            float[][] tokens = ((float[][][]) result.get(0).getValue())[0];
            double squaredNorm = 0;
            double dot = 0;
            for (int i = 0; i < weights.length; i++) {
                double component = 0;
                for (float[] token : tokens) component += token[i];
                squaredNorm += component * component;
                dot += component * weights[i];
            }
            return 1 / (1 + Math.exp(-(bias + dot / Math.max(1e-12, Math.sqrt(squaredNorm)))));
        }
    }
}
