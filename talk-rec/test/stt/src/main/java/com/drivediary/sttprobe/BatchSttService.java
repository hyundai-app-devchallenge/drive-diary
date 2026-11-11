package com.drivediary.sttprobe;

import ai.pleos.playground.stt.SpeechToText;
import ai.pleos.playground.stt.constant.ErrorType;
import ai.pleos.playground.stt.constant.Mode;
import ai.pleos.playground.stt.listener.OnInitializeListener;
import ai.pleos.playground.stt.listener.ResultListener;
import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.util.AtomicFile;
import android.util.Log;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Debug batch file processing; never logs recognition text or exception messages. */
public final class BatchSttService extends Service {
    private static final String CHANNEL = "batch_stt_probe";
    private static boolean running;
    private final Handler main = new Handler(Looper.getMainLooper());
    private File directory;
    private File[] files;
    private PowerManager.WakeLock wake;
    private Clip clip;
    private int completed, errors, speed, padding;
    private String runId, state = "starting", current, reason;
    static boolean isRunning() { return running; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop_batch".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;
        startForeground(4, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        running = true;
        directory = new File(getFilesDir(), "batch");
        runId = intent == null ? UUID.randomUUID().toString()
                : intent.getStringExtra("run_id");
        if (runId == null) runId = UUID.randomUUID().toString();
        speed = intent == null ? 100 : intent.getIntExtra("speed_pct", 100);
        padding = intent == null ? 0 : intent.getIntExtra("padding_ms", 0);
        String only = intent == null ? null : intent.getStringExtra("only");
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                failBatch("batch_directory_unavailable");
                return START_NOT_STICKY;
            }
            // Publish startup before input validation or SDK initialization.
            if (!status()) return START_NOT_STICKY;
            if (speed < 25 || speed > 1600 || (padding != 0 && padding != 500)) {
                failBatch("invalid_options");
                return START_NOT_STICKY;
            }
            if (ContinuousSttService.isRunning() || MicProbeService.isRunning()) {
                failBatch("another_probe_running");
                return START_NOT_STICKY;
            }
            files = directory.listFiles(f -> f.isFile() && f.getName().endsWith(".pcm")
                    && (only == null || f.getName().startsWith(only)));
            if (files == null) {
                failBatch("input_directory_unreadable");
                return START_NOT_STICKY;
            }
            if (files.length == 0) {
                failBatch("no_input_pcm");
                return START_NOT_STICKY;
            }
        } catch (SecurityException error) {
            failBatch("input_directory_unreadable");
            return START_NOT_STICKY;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "DriveDiarySttProbe:batch");
        wake.acquire(6 * 60 * 60 * 1000L);
        state = "running";
        if (!status()) return START_NOT_STICKY;
        next();
        return START_NOT_STICKY;
    }

    private Notification notification() {
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Batch PCM STT", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 4,
                new Intent(this, ProbeActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 4,
                new Intent(this, BatchSttService.class).setAction("stop_batch"), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Drive Diary batch STT").setContentText("Processing internal files/batch PCM")
                .setContentIntent(open).addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true).build();
    }

    private void next() {
        if (!running || !"running".equals(state)) return;
        if (completed == files.length) {
            current = null;
            state = "completed";
            status();
            stopSelf();
            return;
        }
        current = files[completed].getName();
        if (!status()) return;
        clip = new Clip(files[completed]);
        clip.start();
    }

    private void failBatch(String code) {
        state = "error";
        reason = code;
        errors++;
        status();
        stopSelf();
    }

    private boolean status() {
        try {
            JSONObject value = new JSONObject();
            value.put("run_id", runId).put("state", state).put("current", current == null ? JSONObject.NULL : current)
                    .put("completed", completed).put("total", files == null ? 0 : files.length)
                    .put("errors", errors).put("speed_pct", speed).put("padding_ms", padding)
                    .put("reason", reason == null ? JSONObject.NULL : reason);
            atomic(new File(directory, "batch-status.json"), value.toString() + "\n");
            Log.i(ProbeActivity.TAG, "Batch progress: state=" + state + " completed=" + completed
                    + " total=" + (files == null ? 0 : files.length) + " errors=" + errors
                    + (reason == null ? "" : " reason=" + reason));
            return true;
        } catch (Exception error) {
            Log.e(ProbeActivity.TAG, "Batch progress: state=error reason=status_write_failed"
                    + (reason == null ? "" : " input_reason=" + reason));
            state = "error";
            stopSelf();
            return false;
        }
    }

    private static void atomic(File destination, String value) throws IOException {
        AtomicFile file = new AtomicFile(destination);
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(value.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (IOException error) {
            if (output != null) file.failWrite(output);
            throw error;
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }

    private final class Clip implements ResultListener {
        final File source;
        final StringBuilder rows = new StringBuilder("kind\treceived_ms\taudio_ms\ttext\tmetadata\n");
        final Handler timer = new Handler(Looper.getMainLooper());
        SpeechToText sdk;
        byte[] audio;
        volatile long started;
        volatile int sent;
        volatile boolean closed;
        volatile int failures;
        Clip(File source) { this.source = source; }

        void start() {
            try {
                if (source.length() == 0 || source.length() % 2 != 0 || source.length() > 95L * 32000)
                    throw new IOException();
                byte[] pcm = Files.readAllBytes(source.toPath());
                audio = new byte[pcm.length + padding * 64];
                System.arraycopy(pcm, 0, audio, padding * 32, pcm.length);
                // Replace a previous run's output before initializing this clip.
                save();
                timer.postDelayed(() -> fail("initialize_timeout"), 25000);
                sdk = new SpeechToText(getApplicationContext(), Mode.ON_DEVICE);
                sdk.initialize(new OnInitializeListener() {
                    @Override public void onSuccess() { main.post(() -> {
                        if (closed) return;
                        event("initialize_success", "");
                        timer.removeCallbacksAndMessages(null);
                        try {
                            sdk.setLocale(Locale.KOREA);
                            sdk.addListener(Clip.this);
                            started = SystemClock.elapsedRealtime();
                            timer.postDelayed(() -> fail("clip_timeout"), audio.length * 100000L / (32000L * speed) + 35000);
                            checkpoint();
                            send();
                        } catch (RuntimeException error) { fail("setup_exception"); }
                    }); }
                    @Override public void onFailure(ErrorType error) {
                        main.post(() -> fail("initialize_" + error));
                    }
                });
            } catch (IOException | RuntimeException error) { fail("input_or_initialize_exception"); }
        }

        void send() {
            if (closed) return;
            try {
                if (sent == audio.length) {
                    sdk.completeAudioSend();
                    event("audio_complete", "");
                    // No stream-wide terminal callback is promised: retain a full drain window,
                    // including silence/no-result clips and finals after onEndedRecognition.
                    timer.postDelayed(this::finish, 15000);
                    return;
                }
                int end = Math.min(sent + 3200, audio.length);
                byte[] packet = Arrays.copyOfRange(audio, sent, end);
                sent = end;
                sdk.sendAudio(packet);
                long due = started + sent * 100000L / (32000L * speed);
                timer.postDelayed(this::send, Math.max(0, due - SystemClock.elapsedRealtime()));
            } catch (RuntimeException error) { fail("send_exception"); }
        }

        void record(String kind, String text, String metadata) {
            long received = started == 0 ? 0 : SystemClock.elapsedRealtime() - started;
            int position = sent / 32;
            synchronized (rows) {
                if (closed) return;
                rows.append(kind).append('\t').append(received).append('\t').append(position)
                        .append('\t').append(escape(text == null ? "" : text)).append('\t')
                        .append(escape(metadata)).append('\n');
                if ("error".equals(kind)) failures++;
            }
        }
        void event(String name, String value) { record("event", "", name + (value.isEmpty() ? "" : ":" + value)); }
        @Override public void onUpdated(String text, boolean completed) { record(completed ? "final" : "partial", text, ""); }
        @Override public void onError(ErrorType error) {
            record("error", "sdk_" + error, "");
            main.post(() -> { if (!closed) finish(); });
        }
        @Override public void onReady() { event("ready", ""); }
        @Override public void onStartedRecognition() { event("started", ""); }
        @Override public void onEndedRecognition() { event("ended", ""); }
        @Override public void onUpdatedEpdData(long start, long end) { event("epd", start + "," + end); }
        @Override public void onUpdatedRms(double rms) { event("rms", Double.toString(rms)); }

        void fail(String code) {
            if (closed) return;
            record("error", code, "");
            finish();
        }
        void save() throws IOException {
            String snapshot;
            synchronized (rows) { snapshot = rows.toString(); }
            atomic(new File(directory, source.getName().replaceFirst("\\.pcm$", ".tsv")), snapshot);
        }
        void checkpoint() {
            if (closed) return;
            try { save(); } catch (IOException error) { fail("tsv_write_failed"); return; }
            timer.postDelayed(this::checkpoint, 1000);
        }
        void finish() {
            if (closed) return;
            close();
            try { save(); } catch (IOException error) { failures++; state = "failed"; }
            errors += failures;
            completed++;
            clip = null;
            status();
            if ("running".equals(state)) main.postDelayed(BatchSttService.this::next, 300);
            else stopSelf();
        }
        void close() {
            timer.removeCallbacksAndMessages(null);
            if (sdk != null) {
                try { sdk.removeListener(this); } catch (RuntimeException error) { record("error", "remove_listener_exception", ""); }
                try { sdk.release(); } catch (RuntimeException error) { record("error", "release_exception", ""); }
            }
            synchronized (rows) { closed = true; }
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onTimeout(int startId, int fgsType) { stopSelf(); }
    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (clip != null) {
            state = "cancelled";
            clip.fail("service_stopped");
        } else if ("running".equals(state)) {
            state = "cancelled";
            status();
        }
        running = false;
        if (wake != null && wake.isHeld()) wake.release();
        super.onDestroy();
    }
}
