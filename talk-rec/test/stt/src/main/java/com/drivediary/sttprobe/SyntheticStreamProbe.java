package com.drivediary.sttprobe;

import ai.pleos.playground.stt.SpeechToText;
import ai.pleos.playground.stt.constant.ErrorType;
import ai.pleos.playground.stt.constant.Mode;
import ai.pleos.playground.stt.listener.OnInitializeListener;
import ai.pleos.playground.stt.listener.ResultListener;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class SyntheticStreamProbe implements ResultListener, AutoCloseable {
    static final int BYTES_PER_SECOND = 32000;
    private static final int PACKET_MS = 100;
    private static final int PACKET_BYTES = BYTES_PER_SECOND * PACKET_MS / 1000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context app;
    private final byte[] timeline;
    private final Consumer<String> report;
    private final BiConsumer<String, List<String>> completion;
    private final List<String> finals = new ArrayList<>();
    private SpeechToText sdk;
    private boolean closed;
    private long startedAt;

    SyntheticStreamProbe(Context context, byte[] timeline, Consumer<String> report,
            BiConsumer<String, List<String>> completion) {
        this.app = context.getApplicationContext();
        this.timeline = timeline;
        this.report = report;
        this.completion = completion;
    }

    static void place(byte[] timeline, byte[] clip, int second) {
        System.arraycopy(clip, 0, timeline, second * BYTES_PER_SECOND, clip.length);
    }

    void start() {
        main.postDelayed(() -> finish("Pleos stream initialization timed out"), 25000);
        try {
            sdk = new SpeechToText(app, Mode.ON_DEVICE);
            sdk.initialize(new OnInitializeListener() {
                @Override public void onSuccess() { main.post(SyntheticStreamProbe.this::startSending); }
                @Override public void onFailure(ErrorType error) {
                    main.post(() -> finish("Pleos stream initialization failed: " + error));
                }
            });
        } catch (RuntimeException error) {
            finish("Pleos stream setup failed: " + error);
        }
    }

    private void startSending() {
        if (closed) return;
        main.removeCallbacksAndMessages(null);
        try {
            sdk.setLocale(Locale.KOREA);
            sdk.addListener(this);
            startedAt = SystemClock.elapsedRealtime();
            report.accept("Synthetic PCM stream started, audioSeconds=" + timeline.length / BYTES_PER_SECOND);
            sendChunk(0);
        } catch (RuntimeException error) {
            finish("Pleos stream setup failed: " + error);
        }
    }

    private void sendChunk(int offset) {
        if (closed) return;
        try {
            if (offset == timeline.length) {
                sdk.completeAudioSend();
                report.accept("Synthetic PCM stream submitted, elapsedMs=" + elapsed());
                main.postDelayed(() -> finish("Synthetic PCM stream finished"), 8000);
                return;
            }
            int end = Math.min(offset + PACKET_BYTES, timeline.length);
            sdk.sendAudio(Arrays.copyOfRange(timeline, offset, end));
            main.postDelayed(() -> sendChunk(end), PACKET_MS);
        } catch (RuntimeException error) {
            finish("Pleos stream submission failed: " + error);
        }
    }

    @Override public void onUpdated(String text, boolean completed) {
        if (!completed) return;
        main.post(() -> {
            if (closed) return;
            String line = elapsed() + "ms\t" + text;
            finals.add(line);
            report.accept("Stream final " + finals.size() + " at " + line);
        });
    }

    @Override public void onError(ErrorType error) {
        main.post(() -> finish("Pleos stream recognition failed: " + error));
    }

    @Override public void onReady() {}
    @Override public void onStartedRecognition() {}
    @Override public void onEndedRecognition() {}
    @Override public void onUpdatedEpdData(long start, long end) {}
    @Override public void onUpdatedRms(double rms) {}

    private long elapsed() {
        return SystemClock.elapsedRealtime() - startedAt;
    }

    void finish(String status) {
        if (closed) return;
        close();
        completion.accept(status + ", finalResults=" + finals.size(), List.copyOf(finals));
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (sdk == null) return;
        try {
            try {
                sdk.removeListener(this);
            } finally {
                sdk.release();
            }
        } catch (RuntimeException error) {
            report.accept("Pleos stream cleanup failed: " + error);
        }
    }
}
