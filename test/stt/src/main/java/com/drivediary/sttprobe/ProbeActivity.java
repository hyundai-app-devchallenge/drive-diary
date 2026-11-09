package com.drivediary.sttprobe;

import ai.pleos.playground.stt.SpeechToText;
import ai.pleos.playground.stt.constant.ErrorType;
import ai.pleos.playground.stt.constant.Mode;
import ai.pleos.playground.stt.listener.OnInitializeListener;
import ai.pleos.playground.stt.listener.ResultListener;
import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.SpeechRecognizer;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Locale;

public final class ProbeActivity extends Activity {
    static final String TAG = "DriveDiarySttProbe";
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private boolean pcmRunning;
    private boolean capturingFixture;
    private SyntheticStreamProbe shortStream;
    private SpeechToText sdk;
    private final Runnable timeout = () -> finishPcm("No terminal result in 25 seconds");

    private final ResultListener listener = new ResultListener() {
        @Override public void onReady() { report("Pleos ready"); }
        @Override public void onStartedRecognition() { report("Pleos started"); }
        @Override public void onUpdated(String text, boolean completed) {
            if (completed) main.post(() -> finishPcm("Pleos final synthetic transcript: " + text));
        }
        @Override public void onEndedRecognition() { report("Pleos ended"); }
        @Override public void onError(ErrorType error) {
            main.post(() -> finishPcm("Pleos recognition error: " + error));
        }
        @Override public void onUpdatedEpdData(long start, long end) {}
        @Override public void onUpdatedRms(double rms) {}
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(24, 24, 24, 24);
        status = new TextView(this);
        status.setTextSize(18);
        panel.addView(status);
        button(panel, "Run synthetic Korean STT", () -> testPcm(Locale.KOREA, false));
        button(panel, "Run synthetic English STT", () -> testPcm(Locale.US, false));
        button(panel, "Run 30-second synthetic STT", () -> testPcm(Locale.KOREA, true));
        button(panel, "Record virtual answer and transcribe", this::recordVirtualAnswer);
        button(panel, "Run 120-second background STT", this::startContinuous);
        button(panel, "Stop background STT", this::stopContinuous);
        button(panel, "Show last background STT result", this::showContinuousResult);
        button(panel, "Test mic input for 8 seconds", () -> startMic(8));
        button(panel, "Test mic input for 30 seconds (can leave app)", () -> startMic(30));
        button(panel, "Stop mic test", () -> stopService(new Intent(this, MicProbeService.class)));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(panel);
        setContentView(scroll);
        report("Android API " + android.os.Build.VERSION.SDK_INT);
        report("Android recognizer available: " + SpeechRecognizer.isRecognitionAvailable(this));
        report("Android on-device recognizer available: " + SpeechRecognizer.isOnDeviceRecognitionAvailable(this));
        report("Pleos STT_SERVICE granted: " + (checkSelfPermission("pleos.car.permission.STT_SERVICE")
                == PackageManager.PERMISSION_GRANTED));
        report("Mic tests display an active notification and log sample counts only.");
        String synthetic = getIntent().getStringExtra("synthetic");
        if (state == null && "ko-KR".equals(synthetic)) testPcm(Locale.KOREA, false);
        if (state == null && "en-US".equals(synthetic)) testPcm(Locale.US, false);
        if (state == null && "stream".equals(synthetic)) testPcm(Locale.KOREA, true);
        if (state == null && "virtual-answer".equals(synthetic)) recordVirtualAnswer();
        if (state == null && "continuous".equals(synthetic)) startContinuous();
    }

    private void button(LinearLayout panel, String text, Runnable click) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(v -> click.run());
        panel.addView(button);
    }

    private void startMic(int seconds) {
        if (sttBusy()) {
            report("Wait for the synthetic test to finish.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {
                    Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS
            }, 1);
            report("Tap the desired mic test again after granting permission.");
            return;
        }
        startForegroundService(new Intent(this, MicProbeService.class).putExtra("seconds", seconds));
    }

    private void testPcm(Locale locale, boolean stream) {
        if (sttBusy()) return;
        try {
            byte[] pcm = loadFixture(locale);
            if (stream) {
                byte[] timeline = new byte[30 * SyntheticStreamProbe.BYTES_PER_SECOND];
                for (int second : new int[] {0, 13, 26}) SyntheticStreamProbe.place(timeline, pcm, second);
                shortStream = new SyntheticStreamProbe(this, timeline, this::report, (result, finals) -> {
                    report(result);
                    shortStream = null;
                });
                shortStream.start();
            } else {
                recognizePcm(pcm, locale);
            }
        } catch (IOException e) {
            report("Missing synthetic audio asset. See the test README.");
        }
    }

    private byte[] loadFixture(Locale locale) throws IOException {
        try (InputStream input = getAssets().open(locale.toLanguageTag() + ".pcm")) {
            return input.readAllBytes();
        }
    }

    private void recordVirtualAnswer() {
        if (sttBusy()) return;
        try {
            byte[] fixture = loadFixture(Locale.KOREA);
            capturingFixture = true;
            report("Question: 오늘 어떤 추억이 남았나요?");
            report("Virtual input: capturing synthetic answer in 100 ms chunks");
            captureFixture(fixture, new byte[fixture.length], 0);
        } catch (IOException error) {
            report("Could not load the virtual answer: " + error.getMessage());
        }
    }

    private void captureFixture(byte[] fixture, byte[] captured, int offset) {
        if (offset < fixture.length) {
            int count = Math.min(3200, fixture.length - offset);
            System.arraycopy(fixture, offset, captured, offset, count);
            main.postDelayed(() -> captureFixture(fixture, captured, offset + count), 100);
            return;
        }
        capturingFixture = false;
        File recording = new File(getCacheDir(), "virtual-answer.pcm");
        try {
            Files.write(recording.toPath(), captured);
            report("Virtual answer saved locally: " + recording.length() + " bytes");
            recognizePcm(Files.readAllBytes(recording.toPath()), Locale.KOREA);
        } catch (IOException error) {
            report("Virtual answer file failed: " + error.getMessage());
        }
    }

    private void startContinuous() {
        if (sttBusy()) {
            report("Wait for the current STT test to finish.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.POST_NOTIFICATIONS }, 2);
            report("Tap the 120-second test again after granting notification permission.");
            return;
        }
        startForegroundService(new Intent(this, ContinuousSttService.class));
        report("120-second synthetic STT requested. You can leave this screen.");
    }

    private void stopContinuous() {
        if (ContinuousSttService.isRunning()) {
            startService(new Intent(this, ContinuousSttService.class).setAction(ContinuousSttService.STOP));
        }
    }

    private boolean sttBusy() {
        return pcmRunning || capturingFixture || shortStream != null || ContinuousSttService.isRunning();
    }

    private void showContinuousResult() {
        File file = new File(getCacheDir(), ContinuousSttService.RESULT_FILE);
        try {
            status.append(Files.readString(file.toPath()));
        } catch (IOException error) {
            report("No completed background STT result available.");
        }
    }

    private void recognizePcm(byte[] pcm, Locale locale) {
        pcmRunning = true;
        report("Pleos ON_DEVICE: " + locale.toLanguageTag() + ", " + pcm.length + " bytes of synthetic PCM");
        main.postDelayed(timeout, 25000);
        try {
            SpeechToText instance = new SpeechToText(getApplicationContext(), Mode.ON_DEVICE);
            sdk = instance;
            instance.initialize(new OnInitializeListener() {
                @Override public void onSuccess() {
                    main.post(() -> {
                        if (sdk != instance) return;
                        report("Pleos init success");
                        try {
                            instance.setLocale(locale);
                            instance.addListener(listener);
                            instance.sendAudio(pcm);
                            instance.completeAudioSend();
                            report("Pleos PCM submitted");
                        } catch (RuntimeException error) {
                            finishPcm("Pleos submission failed: " + error);
                        }
                    });
                }
                @Override public void onFailure(ErrorType error) {
                    main.post(() -> finishPcm("Pleos init failed: " + error));
                }
            });
        } catch (RuntimeException error) {
            finishPcm("Pleos setup failed: " + error);
        }
    }

    private void finishPcm(String outcome) {
        if (!pcmRunning) return;
        report(outcome);
        main.removeCallbacks(timeout);
        pcmRunning = false;
        releasePcm();
    }

    private void releasePcm() {
        SpeechToText instance = sdk;
        sdk = null;
        if (instance == null) return;
        try {
            instance.removeListener(listener);
        } finally {
            instance.release();
        }
    }

    private void report(String message) {
        Log.i(TAG, message);
        runOnUiThread(() -> status.append(message + "\n"));
    }

    @Override protected void onStop() {
        Log.i(TAG, "Probe activity stopped");
        super.onStop();
    }

    @Override protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (shortStream != null) shortStream.close();
        releasePcm();
        super.onDestroy();
    }
}
