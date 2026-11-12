package com.drivediary.sttprobe;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** Feeds actual microphone frames to the same RAM buffer and STT used by fixture replay. */
final class LiveStoryInput implements AutoCloseable {
    private static final int RATE = 16000;
    private final Context context;
    private final RamStoryProbe probe;
    private final Consumer<String> report;
    private final Runnable onError;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final short[] samples = new short[RamSpeechBuffer.FRAME_BYTES / 2];
    private final byte[] frame = new byte[RamSpeechBuffer.FRAME_BYTES];
    private final Runnable poll = this::read;
    private final AudioManager.AudioRecordingCallback callback = new AudioManager.AudioRecordingCallback() {
        @Override public void onRecordingConfigChanged(List<AudioRecordingConfiguration> configs) {
            if (recorder != null) updateRoute(recorder.getActiveRecordingConfiguration());
        }
    };
    private AudioRecord recorder;
    private boolean audioAvailable;
    private boolean callbackRegistered;
    private int route = -1;
    private int filled;
    private int peak;
    private long samplesRead;
    private long started;
    private long deadline;
    private long nextReport;

    LiveStoryInput(Context context, RamStoryProbe probe, Consumer<String> report, Runnable onError) {
        this.context = context;
        this.probe = probe;
        this.report = report;
        this.onError = onError;
    }

    void start(int seconds) {
        int channels = AudioFormat.CHANNEL_IN_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int minimum = AudioRecord.getMinBufferSize(RATE, channels, encoding);
        if (minimum <= 0) throw new IllegalStateException("Unsupported mic format: " + minimum);
        recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, channels, encoding,
                Math.max(minimum, RATE / 5));
        recorder.registerAudioRecordingCallback(context.getMainExecutor(), callback);
        callbackRegistered = true;
        recorder.startRecording();
        if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            throw new IllegalStateException("Microphone did not enter RECORDING");
        }
        updateRoute(recorder.getActiveRecordingConfiguration());
        started = SystemClock.elapsedRealtime();
        deadline = started + seconds * 1000L;
        nextReport = started + 10000;
        report.accept("Live story capture started duration_s=" + seconds);
        handler.post(poll);
    }

    private void updateRoute(AudioRecordingConfiguration config) {
        AudioDeviceInfo device = config == null ? null : config.getAudioDevice();
        int nextRoute = device == null ? -1 : device.getId();
        boolean available = config != null && !config.isClientSilenced() && device != null;
        if (nextRoute == route && available == audioAvailable) return;
        route = nextRoute;
        audioAvailable = available;
        filled = 0;
        Arrays.fill(samples, (short) 0);
        Arrays.fill(frame, (byte) 0);
        probe.interruptLiveInput();
        report.accept("Live story input available=" + available + " route=" + route);
    }

    private void read() {
        long now = SystemClock.elapsedRealtime();
        if (now >= deadline) {
            close();
            report.accept("Live story capture completed elapsed_ms=" + (now - started)
                    + " samples=" + samplesRead + " last_peak=" + peak);
            probe.endLiveInput();
            return;
        }
        try {
            int count = recorder.read(samples, filled, samples.length - filled, AudioRecord.READ_NON_BLOCKING);
            if (count < 0) throw new IllegalStateException("AudioRecord.read: " + count);
            samplesRead += count;
            filled += count;
            if (filled == samples.length) {
                if (audioAvailable) {
                    for (int i = 0; i < samples.length; i++) {
                        short sample = samples[i];
                        peak = Math.max(peak, Math.abs((int) sample));
                        frame[2 * i] = (byte) sample;
                        frame[2 * i + 1] = (byte) (sample >> 8);
                    }
                    probe.onFrame(frame);
                }
                filled = 0;
            }
            if (now >= nextReport) {
                report.accept("Live story capture elapsed_ms=" + (now - started)
                        + " samples=" + samplesRead + " peak=" + peak + " available=" + audioAvailable);
                peak = 0;
                nextReport += 10000;
            }
            handler.postDelayed(poll, count == samples.length ? 0 : 10);
        } catch (RuntimeException error) {
            report.accept("Live story capture failed: " + error.getClass().getSimpleName());
            onError.run();
        }
    }

    @Override public void close() {
        handler.removeCallbacks(poll);
        Arrays.fill(samples, (short) 0);
        Arrays.fill(frame, (byte) 0);
        AudioRecord instance = recorder;
        recorder = null;
        if (instance == null) return;
        try {
            if (callbackRegistered) instance.unregisterAudioRecordingCallback(callback);
            if (instance.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) instance.stop();
        } catch (RuntimeException error) {
            report.accept("Live story microphone cleanup failed");
        } finally {
            instance.release();
        }
    }
}
