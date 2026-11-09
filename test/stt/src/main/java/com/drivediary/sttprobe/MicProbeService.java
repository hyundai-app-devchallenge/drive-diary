package com.drivediary.sttprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

public final class MicProbeService extends Service {
    private static final String CHANNEL = "mic_probe";
    private static final String STOP = "stop";
    private static final int RATE = 16000;
    private volatile boolean stopped;
    private Thread worker;

    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null || STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (worker != null) return START_NOT_STICKY;

        boolean gap = intent.getBooleanExtra("gap", false);
        int seconds = gap ? 6 : (intent.getIntExtra("seconds", 8) == 30 ? 30 : 8);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Mic probe",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0, new Intent(this, MicProbeService.class)
                .setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Drive Diary microphone test")
                .setContentText(gap ? "6s input, 10s closed, 6s input. Sample counts only."
                        : "Sample counts only. Stops automatically after " + seconds + " seconds.")
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);

        worker = new Thread(() -> runProbe(seconds, gap), "mic-probe");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runProbe(int seconds, boolean gap) {
        try {
            if (measure(seconds) && gap && !stopped) {
                long closedAt = SystemClock.elapsedRealtime();
                Log.i(ProbeActivity.TAG, "Mic gap started after AudioRecord release");
                while (!stopped && SystemClock.elapsedRealtime() - closedAt < 10000) {
                    SystemClock.sleep(100);
                }
                if (!stopped) {
                    Log.i(ProbeActivity.TAG, "Mic gap completed, closedMs="
                            + (SystemClock.elapsedRealtime() - closedAt));
                    measure(seconds);
                }
            }
        } finally {
            Log.i(ProbeActivity.TAG, "Mic stopped");
            stopSelf();
        }
    }

    private boolean measure(int seconds) {
        int channels = AudioFormat.CHANNEL_IN_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        AudioRecord recorder = null;
        try {
            int minimum = AudioRecord.getMinBufferSize(RATE, channels, encoding);
            if (minimum <= 0) throw new IllegalStateException("Unsupported mic format: " + minimum);
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, channels, encoding,
                    Math.max(minimum, RATE / 5));
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Microphone did not enter RECORDING");
            }
            short[] samples = new short[1600];
            long started = SystemClock.elapsedRealtime();
            long nextReport = started + 1000;
            long deadline = started + seconds * 1000L;
            int frames = 0;
            int peak = 0;
            Log.i(ProbeActivity.TAG, "Mic started for " + seconds + " seconds");
            while (!stopped && SystemClock.elapsedRealtime() < deadline) {
                int count = recorder.read(samples, 0, samples.length, AudioRecord.READ_NON_BLOCKING);
                if (count < 0) throw new IllegalStateException("AudioRecord.read: " + count);
                if (count == 0) SystemClock.sleep(20);
                frames += count;
                for (int i = 0; i < count; i++) peak = Math.max(peak, Math.abs((int) samples[i]));
                long now = SystemClock.elapsedRealtime();
                if (now >= nextReport) {
                    AudioRecordingConfiguration config = recorder.getActiveRecordingConfiguration();
                    Log.i(ProbeActivity.TAG, "Mic elapsedMs=" + (now - started) + " frames=" + frames
                            + " peak=" + peak + " silenced=" + (config == null ? "unknown" : config.isClientSilenced()));
                    nextReport = now + 1000;
                    frames = 0;
                    peak = 0;
                }
            }
            return true;
        } catch (RuntimeException error) {
            Log.w(ProbeActivity.TAG, "Mic failed", error);
            return false;
        } finally {
            if (recorder != null) releaseRecorder(recorder);
        }
    }

    private void releaseRecorder(AudioRecord recorder) {
        try {
            if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) recorder.stop();
        } catch (IllegalStateException error) {
            Log.w(ProbeActivity.TAG, "Could not stop AudioRecord cleanly", error);
        } finally {
            recorder.release();
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        stopped = true;
        super.onDestroy();
    }
}
