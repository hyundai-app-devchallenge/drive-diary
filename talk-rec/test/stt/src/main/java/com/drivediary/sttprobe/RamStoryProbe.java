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
import android.util.AtomicFile;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Replay fixtures through bounded RAM capture, real Pleos STT, and the local classifier. */
final class RamStoryProbe implements AutoCloseable, RamStoryTimeline.Listener {
    private static final String[] CLIPS = {
        "e01", "e02", "e03", "e04", "e05", "e06", "e15", "e16", "e17", "e18", "e29", "e30"
    };
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context app;
    private final Consumer<String> report;
    private final Runnable done;
    private final TinyStoryModel model;
    private final List<String> candidates = new ArrayList<>();
    private final Locale locale;
    private final RamSpeechBuffer buffer = new RamSpeechBuffer(this::onSpeech);
    private RamStoryTimeline timeline;
    private RamStoryTimeline.Clip clip;
    private BufferedWriter candidateOutput;
    private Session active;
    private Session pending;
    private int index;
    private int errors;
    private int segmentsForClip;
    private int missing;
    private int extra;
    private int dropped;
    private int transcribed;
    private int saved;
    private int saveErrors;
    private int cleanupErrors;
    private int truePositives;
    private int falsePositives;
    private boolean live;
    private boolean inputEnded;
    private boolean closed;

    RamStoryProbe(Context context, Locale locale, Consumer<String> report, Runnable done)
            throws IOException {
        app = context.getApplicationContext();
        this.locale = locale;
        this.report = report;
        this.done = done;
        model = new TinyStoryModel(app, "feature2/story-model-mixed.tsv");
    }

    void startTimeline(int seconds) throws IOException {
        candidateOutput = Files.newBufferedWriter(new File(app.getCacheDir(),
                "ram-story-soak-" + seconds + "s-candidates.txt").toPath(), StandardCharsets.UTF_8);
        timeline = new RamStoryTimeline(main, app.getAssets(), CLIPS, seconds, this, error -> {
            report.accept("RAM soak input error: " + error);
            stop();
        });
        report.accept("RAM soak start duration_s=" + seconds + " expected=" + timeline.expectedClips());
        timeline.start();
    }

    void startLive() throws IOException {
        candidateOutput = Files.newBufferedWriter(new File(app.getCacheDir(),
                "live-story-candidates.txt").toPath(), StandardCharsets.UTF_8);
        live = true;
    }

    void interruptLiveInput() {
        buffer.reset();
        if (pending != null) {
            pending.release();
            pending = null;
            dropped++;
        }
    }

    void endLiveInput() {
        inputEnded = true;
        buffer.reset();
        finishIfDrained();
    }

    @Override public void onClip(RamStoryTimeline.Clip current) {
        checkMissingClip();
        clip = current;
        segmentsForClip = 0;
    }

    @Override public void onFrame(byte[] frame) {
        buffer.accept(frame);
    }

    @Override public void onEnd(int frames, long elapsedMs, long maxLateMs, int lateFrames) {
        checkMissingClip();
        inputEnded = true;
        buffer.reset();
        report.accept("RAM soak input complete frames=" + frames + " elapsed_ms=" + elapsedMs
                + " max_late_ms=" + maxLateMs + " late_frames_100ms=" + lateFrames);
        finishIfDrained();
    }

    private void checkMissingClip() {
        if (clip != null && segmentsForClip == 0) {
            missing++;
            report.accept("RAM soak no speech clip=" + clip.number + " id=" + clip.id);
        }
    }

    private void onSpeech(byte[] pcm, int length) {
        if (live) {
            enqueue(new Session(null, ++index, Arrays.copyOf(pcm, length)));
            return;
        }
        if (++segmentsForClip != 1 || clip == null) {
            extra++;
            if (timeline != null) report.accept("RAM soak extra speech clip=" + (clip == null ? "none" : clip.number));
            return;
        }
        enqueue(new Session(clip, clip.number, Arrays.copyOf(pcm, length)));
    }

    private void enqueue(Session session) {
        if (active == null) {
            active = session;
            active.start();
        } else {
            if (pending != null) {
                dropped++;
                report.accept(live ? "Live story queue drop segment=" + pending.number
                        : "RAM soak queue drop clip=" + pending.clip.number + " id=" + pending.clip.id);
                pending.release();
            }
            pending = session;
        }
    }

    private void finishIfDrained() {
        if (!inputEnded || active != null || pending != null) return;
        String results = " transcribed=" + transcribed + " errors=" + errors;
        String scope = live ? "Live story completed detected=" + index + results
                : "RAM soak completed expected=" + timeline.expectedClips() + results
                    + " missing=" + missing + " extra=" + extra;
        report.accept(scope
                + " queue_dropped=" + dropped + " saved=" + saved + " save_errors=" + saveErrors
                + " cleanup_errors=" + cleanupErrors
                + (live ? "" : " tp=" + truePositives + " fp=" + falsePositives));
        stop();
    }

    private void stop() {
        close();
        done.run();
    }

    void next() {
        if (closed) return;
        if (index == CLIPS.length) {
            try {
                save();
                report.accept("RAM story completed locale=" + locale.toLanguageTag()
                        + " clips=" + index + " errors=" + errors + " kept=" + candidates.size());
            } catch (IOException error) {
                report.accept("RAM story could not write candidate text: " + error.getMessage());
            } finally {
                stop();
            }
            return;
        }
        String id = CLIPS[index++];
        clip = new RamStoryTimeline.Clip(index, id, 0);
        segmentsForClip = 0;
        byte[] frame = new byte[RamSpeechBuffer.FRAME_BYTES];
        try (InputStream input = app.getAssets().open("story-fixtures/" + id + ".pcm")) {
            for (int size; (size = input.readNBytes(frame, 0, frame.length)) != 0;) {
                Arrays.fill(frame, size, frame.length, (byte) 0);
                buffer.accept(frame);
            }
            Arrays.fill(frame, (byte) 0);
            for (int i = 0; i < 50; i++) buffer.accept(frame);
        } catch (IOException error) {
            report.accept("Generate story fixtures with tools/make-story-fixtures.ps1: " + error.getMessage());
            stop();
            return;
        } finally {
            buffer.reset();
            Arrays.fill(frame, (byte) 0);
        }
        if (segmentsForClip == 0) {
            errors++;
            report.accept("RAM story no speech id=" + id);
            main.post(this::next);
            return;
        }
    }

    private void save() throws IOException {
        AtomicFile file = new AtomicFile(new File(app.getCacheDir(),
                "ram-story-candidates-" + locale.toLanguageTag() + ".txt"));
        FileOutputStream output = file.startWrite();
        try {
            output.write(String.join("\n", candidates).getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException error) {
            file.failWrite(output);
            throw error;
        }
    }

    @Override public void close() {
        closed = true;
        main.removeCallbacksAndMessages(null);
        if (timeline != null) timeline.close();
        buffer.reset();
        if (active != null) {
            active.release();
            active = null;
        }
        if (pending != null) {
            pending.release();
            pending = null;
        }
        if (candidateOutput != null) {
            try {
                candidateOutput.close();
            } catch (IOException error) {
                report.accept("RAM soak candidate file close failed: " + error);
            }
            candidateOutput = null;
        }
        candidates.clear();
    }

    private final class Session implements ResultListener {
        private final RamStoryTimeline.Clip clip;
        private final int number;
        private final byte[] pcm;
        private final long detectedAt = SystemClock.elapsedRealtime();
        private final List<String> finals = new ArrayList<>();
        private final Runnable timeout = () -> finish("timeout");
        private final Runnable settle = () -> finish(null);
        private SpeechToText sdk;
        private long sentAt;
        private long firstFinalAt;

        Session(RamStoryTimeline.Clip clip, int number, byte[] pcm) {
            this.clip = clip;
            this.number = number;
            this.pcm = pcm;
        }

        void start() {
            main.postDelayed(timeout, 10000);
            try {
                sdk = new SpeechToText(app, Mode.ON_DEVICE);
                sdk.initialize(new OnInitializeListener() {
                    @Override public void onSuccess() { main.post(Session.this::submit); }
                    @Override public void onFailure(ErrorType error) {
                        main.post(() -> finish(error.toString()));
                    }
                });
            } catch (RuntimeException error) {
                finish(error.toString());
            }
        }

        private void submit() {
            if (active != this) return;
            try {
                sdk.setLocale(locale);
                sdk.addListener(this);
                sentAt = SystemClock.elapsedRealtime();
                sdk.sendAudio(pcm);
                sdk.completeAudioSend();
            } catch (RuntimeException error) {
                finish(error.toString());
            }
        }

        @Override public void onUpdated(String text, boolean completed) {
            if (!completed || text.isBlank()) return;
            main.post(() -> {
                if (active != this) return;
                if (finals.isEmpty()) firstFinalAt = SystemClock.elapsedRealtime();
                finals.add(text.trim());
                main.removeCallbacks(settle);
                main.postDelayed(settle, 900);
            });
        }

        private void finish(String error) {
            if (active != this) return;
            active = null;
            release();
            if (live || timeline != null) {
                if (live) reportLiveResult(error);
                else reportTimelineResult(error);
                if (pending != null) {
                    active = pending;
                    pending = null;
                    active.start();
                }
                finishIfDrained();
                return;
            }
            if (error != null) {
                errors++;
                report.accept("RAM story error id=" + clip.id + " error=" + error);
            } else {
                String transcript = String.join(" ", finals);
                double score = model.score(transcript);
                boolean keep = score >= model.threshold;
                if (keep) candidates.add(transcript);
                // These are synthetic fixtures only. Live input must never log transcript text.
                report.accept(String.format(Locale.ROOT,
                        "RAM story STT\t%s\t%s\t%d\t%d\t%.3f\t%s\t%s",
                        locale.toLanguageTag(), clip.id, pcm.length, SystemClock.elapsedRealtime() - sentAt,
                        score, keep ? "keep" : "drop", transcript.replace('\t', ' ')));
            }
            main.postDelayed(RamStoryProbe.this::next, 200);
        }

        private void reportTimelineResult(String error) {
            if (error != null) {
                errors++;
                report.accept("RAM soak STT error clip=" + clip.number + " id=" + clip.id
                        + " second=" + clip.second + " error=" + error);
                return;
            }
            transcribed++;
            String transcript = String.join(" ", finals).replace('\t', ' ').replace('\n', ' ');
            double score = model.score(transcript);
            boolean keep = score >= model.threshold;
            if (keep) {
                if (clip.expectedKeep()) truePositives++; else falsePositives++;
                saveCandidate(transcript);
            }
            report.accept(String.format(Locale.ROOT,
                    "RAM soak result\t%d\t%s\t%d\t%d\t%d\t%d\t%d\t%d\t%.3f\t%s\t%s",
                    clip.number, clip.id, clip.second, timeline.elapsedMs(), pcm.length,
                    sentAt - detectedAt, firstFinalAt - sentAt, SystemClock.elapsedRealtime() - detectedAt,
                    score, keep ? "keep" : "drop", transcript));
        }

        private void reportLiveResult(String error) {
            if (error != null) {
                errors++;
                report.accept("Live story STT error segment=" + number + " error=" + error);
                return;
            }
            transcribed++;
            String transcript = String.join(" ", finals).replace('\r', ' ').replace('\n', ' ');
            double score = model.score(transcript);
            boolean keep = score >= model.threshold;
            if (keep) saveCandidate(transcript);
            // Real-microphone transcripts are never included in this app's diagnostic log.
            report.accept(String.format(Locale.ROOT,
                    "Live story result segment=%d bytes=%d final_ms=%d decision_ms=%d score=%.3f keep=%s",
                    number, pcm.length, firstFinalAt - sentAt, SystemClock.elapsedRealtime() - detectedAt,
                    score, keep));
        }

        private void release() {
            main.removeCallbacks(timeout);
            main.removeCallbacks(settle);
            try {
                if (sdk != null) {
                    try {
                        sdk.removeListener(this);
                    } finally {
                        sdk.release();
                    }
                }
            } catch (RuntimeException error) {
                cleanupErrors++;
                report.accept("RAM story STT cleanup failed");
            } finally {
                Arrays.fill(pcm, (byte) 0);
            }
        }

        @Override public void onError(ErrorType error) { main.post(() -> finish(error.toString())); }
        @Override public void onReady() {}
        @Override public void onStartedRecognition() {}
        @Override public void onEndedRecognition() {}
        @Override public void onUpdatedEpdData(long start, long end) {}
        @Override public void onUpdatedRms(double rms) {}
    }

    private void saveCandidate(String transcript) {
        try {
            candidateOutput.write(transcript);
            candidateOutput.newLine();
            candidateOutput.flush();
            saved++;
        } catch (IOException error) {
            saveErrors++;
            report.accept(live ? "Live story candidate write failed" : "RAM soak candidate write failed");
        }
    }
}
