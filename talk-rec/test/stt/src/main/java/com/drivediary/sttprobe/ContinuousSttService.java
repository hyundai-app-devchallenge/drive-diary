package com.drivediary.sttprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class ContinuousSttService extends Service {
    static final String RESULT_FILE = "continuous-stt.txt";
    private static final int SECONDS = 120;
    private static final String CHANNEL = "continuous_stt_probe";
    static final String STOP = "stop";
    private static boolean running;
    private SyntheticStreamProbe probe;

    static boolean isRunning() { return running; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) {
            if (probe == null) stopSelf();
            else probe.finish("Synthetic PCM stream stopped by user");
            return START_NOT_STICKY;
        }
        if (probe != null) return START_NOT_STICKY;

        startForeground(2, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
        running = true;
        try {
            byte[] timeline = new byte[SECONDS * SyntheticStreamProbe.BYTES_PER_SECOND];
            byte[] ocean = load("ko-KR.pcm");
            byte[] friends = load("ko-friends.pcm");
            byte[] sunset = load("ko-sunset.pcm");
            SyntheticStreamProbe.place(timeline, ocean, 4);
            SyntheticStreamProbe.place(timeline, friends, 24);
            SyntheticStreamProbe.place(timeline, sunset, 54);
            SyntheticStreamProbe.place(timeline, friends, 87);
            SyntheticStreamProbe.place(timeline, ocean, 114);
            probe = new SyntheticStreamProbe(this, timeline, this::log, this::finishRun);
            probe.start();
        } catch (IOException error) {
            finishRun("Could not load the synthetic timeline: " + error.getMessage(), List.of());
        }
        return START_NOT_STICKY;
    }

    private byte[] load(String filename) throws IOException {
        try (InputStream input = getAssets().open(filename)) {
            return input.readAllBytes();
        }
    }

    private Notification notification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Continuous STT probe",
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, ContinuousSttService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Drive Diary synthetic STT")
                .setContentText("120-second synthetic audio test")
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
    }

    private void finishRun(String status, List<String> finals) {
        log(status);
        AtomicFile file = new AtomicFile(new File(getCacheDir(), RESULT_FILE));
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            String result = status + "\n" + String.join("\n", finals) + "\n";
            output.write(result.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
            log("Continuous transcript saved: " + RESULT_FILE);
        } catch (IOException error) {
            if (output != null) file.failWrite(output);
            log("Could not save the continuous transcript: " + error.getMessage());
        } finally {
            stopSelf();
        }
    }

    private void log(String message) {
        Log.i(ProbeActivity.TAG, "Continuous STT: " + message);
    }

    @Override public void onTimeout(int startId) {
        if (probe == null) stopSelf();
        else probe.finish("Continuous STT service timed out");
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        if (probe != null) probe.close();
        running = false;
        log("Service stopped");
        super.onDestroy();
    }
}
