package com.drivediary.sttprobe;

import android.content.res.AssetManager;
import android.os.Handler;
import android.os.SystemClock;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.function.Consumer;

/** Streams one 20 ms frame at a time from TTS fixtures and generated silence. */
final class RamStoryTimeline implements AutoCloseable {
    private static final int FRAME_MS = 20;
    private static final int[][] OFFSETS = {{3, 12, 20, 45}, {2, 10, 18, 26}, {6, 19, 35, 50}};

    static final class Clip {
        final int number;
        final String id;
        final int second;

        Clip(int number, String id, int second) {
            this.number = number;
            this.id = id;
            this.second = second;
        }

        boolean expectedKeep() {
            return Integer.parseInt(id.substring(1)) % 2 == 1;
        }
    }

    interface Listener {
        void onClip(Clip clip);
        void onFrame(byte[] frame);
        void onEnd(int frames, long elapsedMs, long maxLateMs, int lateFrames);
    }

    private final Handler handler;
    private final AssetManager assets;
    private final String[] ids;
    private final int seconds;
    private final Listener listener;
    private final Consumer<IOException> onError;
    private final byte[] frame = new byte[RamSpeechBuffer.FRAME_BYTES];
    private final Runnable tick = this::feed;
    private InputStream input;
    private long started;
    private long maxLateMs;
    private int lateFrames;
    private int frames;
    private int clips;

    RamStoryTimeline(Handler handler, AssetManager assets, String[] ids, int seconds,
            Listener listener, Consumer<IOException> onError) {
        this.handler = handler;
        this.assets = assets;
        this.ids = ids;
        this.seconds = seconds;
        this.listener = listener;
        this.onError = onError;
    }

    int expectedClips() {
        int minutes = seconds / 60;
        int count = minutes * OFFSETS[0].length;
        for (int offset : OFFSETS[minutes % OFFSETS.length]) {
            if (offset + 8 <= seconds % 60) count++;
        }
        return count;
    }

    long elapsedMs() {
        return SystemClock.uptimeMillis() - started;
    }

    void start() {
        started = SystemClock.uptimeMillis();
        handler.postAtTime(tick, started);
    }

    private void feed() {
        int positionMs = frames * FRAME_MS;
        long late = elapsedMs() - positionMs;
        maxLateMs = Math.max(maxLateMs, late);
        if (late >= 100) lateFrames++;
        if (positionMs == seconds * 1000) {
            listener.onEnd(frames, elapsedMs(), maxLateMs, lateFrames);
            return;
        }
        int minute = clips / OFFSETS[0].length;
        int second = minute * 60 + OFFSETS[minute % OFFSETS.length][clips % OFFSETS[0].length];
        try {
            if (positionMs == second * 1000 && second + 8 <= seconds) {
                if (input != null) throw new IOException("Overlapping fixtures at second " + second);
                String id = ids[clips % ids.length];
                input = assets.open("story-fixtures/" + id + ".pcm");
                listener.onClip(new Clip(++clips, id, second));
            }
            int size = input == null ? 0 : input.readNBytes(frame, 0, frame.length);
            Arrays.fill(frame, size, frame.length, (byte) 0);
            if (input != null && size < frame.length) closeInput();
            listener.onFrame(frame);
        } catch (IOException error) {
            close();
            onError.accept(error);
            return;
        }
        handler.postAtTime(tick, started + ++frames * (long) FRAME_MS);
    }

    private void closeInput() throws IOException {
        InputStream opened = input;
        input = null;
        if (opened != null) opened.close();
    }

    @Override public void close() {
        handler.removeCallbacks(tick);
        Arrays.fill(frame, (byte) 0);
        try {
            closeInput();
        } catch (IOException error) {
            android.util.Log.w(ProbeActivity.TAG, "Could not close synthetic audio fixture", error);
        }
    }
}
