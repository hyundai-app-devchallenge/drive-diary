package com.drivediary.sttprobe;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class TinyStoryModel {
    static final String MODEL_ASSET = "feature2/story-model.tsv";
    static final double THRESHOLD = 0.65;
    private static final Pattern SEPARATOR = Pattern.compile("[^\\p{L}\\p{N}]+");
    private final Map<String, Double> weights = new HashMap<>();
    private final double bias;

    TinyStoryModel(Context context) throws IOException {
        try (BufferedReader reader = reader(context, MODEL_ASSET)) {
            String[] header = reader.readLine().split("\t", 2);
            bias = Double.parseDouble(header[1]);
            for (String line; (line = reader.readLine()) != null;) {
                String[] pair = line.split("\t", 2);
                weights.put(pair[0], Double.parseDouble(pair[1]));
            }
        }
    }

    static BufferedReader reader(Context context, String name) throws IOException {
        return new BufferedReader(new InputStreamReader(context.getAssets().open(name), StandardCharsets.UTF_8));
    }

    double score(String input) {
        int end = input.offsetByCodePoints(0, Math.min(512, input.codePointCount(0, input.length())));
        String text = Normalizer.normalize(input.substring(0, end), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        String cleaned = SEPARATOR.matcher(text).replaceAll(" ").trim().replace(' ', '_');
        int[] codepoints = ("_" + cleaned + "_").codePoints().toArray();
        Set<String> seen = new HashSet<>();
        double sum = 0;
        for (int n = 2; n <= 4; n++) {
            for (int i = 0; i + n <= codepoints.length; i++) {
                String feature = new String(codepoints, i, n);
                Double weight = weights.get(feature);
                if (weight != null && seen.add(feature)) sum += weight;
            }
        }
        return 1 / (1 + Math.exp(-(bias + sum / Math.sqrt(Math.max(1, seen.size())))));
    }
}
