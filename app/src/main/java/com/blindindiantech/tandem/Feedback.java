package com.blindindiantech.tandem;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.speech.tts.TextToSpeech;

import java.util.Locale;
import java.util.Random;

/** Text-to-speech plus synthesized sound effects (no audio asset files needed). */
final class Feedback {
    private static final int SR = 22050;
    private static final AudioAttributes ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
    private static final Random RND = new Random(7);

    private static TextToSpeech tts;
    private static volatile boolean ready;
    private static String pending;
    private static volatile boolean looping;

    private static short[] soundBeep;
    private static short[] soundLoud;
    private static short[] soundSurge;
    private static short[] soundRelease;

    private Feedback() {}

    static synchronized void init(Context context) {
        if (tts != null) return;
        final Context app = context.getApplicationContext();
        tts = new TextToSpeech(app, status -> {
            synchronized (Feedback.class) {
                if (status != TextToSpeech.SUCCESS) return;
                int r = tts.setLanguage(Locale.getDefault());
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                tts.setAudioAttributes(ATTRS);
                ready = true;
                if (pending != null) {
                    String p = pending;
                    pending = null;
                    tts.speak(p, TextToSpeech.QUEUE_FLUSH, null, "tandem");
                }
            }
        });
    }

    static synchronized void speak(String text) {
        if (tts == null) return;
        if (ready) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tandem");
        } else {
            pending = text;
        }
    }

    // ---- Server start-up feedback -------------------------------------------------

    /** Looping beep with spoken "Setting up, please wait..." until {@link #setupReady()}. */
    static synchronized void startSetupLoop() {
        if (looping) return;
        looping = true;
        speak("Setting up, please wait...");
        final short[] beep = beep();
        Thread t = new Thread(() -> {
            long lastSpoken = System.currentTimeMillis();
            while (looping) {
                play(beep);
                sleepQuiet(650);
                if (looping && System.currentTimeMillis() - lastSpoken > 9000) {
                    lastSpoken = System.currentTimeMillis();
                    speak("Setting up, please wait...");
                }
            }
        }, "setup-loop");
        t.setDaemon(true);
        t.start();
    }

    /** Stops the loop, plays a loud beep, then says "Setup is ready". */
    static void setupReady() {
        looping = false;
        runAsync(() -> {
            sleepQuiet(300);
            play(loud());
            speak("Setup is ready");
        });
    }

    static void stopSetupLoop() {
        looping = false;
    }

    // ---- Connection feedback ------------------------------------------------------

    static void surge(final String phrase) {
        runAsync(() -> {
            play(surgeSound());
            speak(phrase);
        });
    }

    static void release(final String phrase) {
        runAsync(() -> {
            play(releaseSound());
            speak(phrase);
        });
    }

    // ---- Playback -----------------------------------------------------------------

    private static void runAsync(Runnable r) {
        Thread t = new Thread(r, "feedback");
        t.setDaemon(true);
        t.start();
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /** Plays PCM and blocks until it has finished. */
    private static void play(short[] pcm) {
        AudioTrack track = null;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(ATTRS)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(pcm.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build();
            track.write(pcm, 0, pcm.length);
            track.play();
            sleepQuiet(pcm.length * 1000L / SR + 80);
        } catch (Exception ignored) {
            // Never let a sound failure break the app.
        } finally {
            if (track != null) {
                try {
                    track.stop();
                } catch (Exception ignored) {
                }
                track.release();
            }
        }
    }

    // ---- Synthesis ----------------------------------------------------------------

    private static short clip(double s, double gain) {
        return (short) (Math.max(-1.0, Math.min(1.0, s)) * 32767 * gain);
    }

    private static synchronized short[] beep() {
        if (soundBeep == null) {
            int n = SR * 140 / 1000;
            short[] o = new short[n];
            for (int i = 0; i < n; i++) {
                double env = Math.min(1.0, Math.min(i, n - 1 - i) / (SR * 0.01));
                o[i] = clip(Math.sin(2 * Math.PI * 660 * i / SR) * env, 0.5);
            }
            soundBeep = o;
        }
        return soundBeep;
    }

    private static synchronized short[] loud() {
        if (soundLoud == null) {
            int n = SR * 550 / 1000;
            short[] o = new short[n];
            for (int i = 0; i < n; i++) {
                double env = Math.min(1.0, Math.min(i, n - 1 - i) / (SR * 0.01));
                double s = 0.6 * Math.sin(2 * Math.PI * 1000 * i / SR)
                        + 0.4 * Math.sin(2 * Math.PI * 1500 * i / SR);
                o[i] = clip(s * env, 0.98);
            }
            soundLoud = o;
        }
        return soundLoud;
    }

    /** Electric power-surge: rising sweep, buzzing hum and crackle, ending in a zap. */
    private static synchronized short[] surgeSound() {
        if (soundSurge == null) {
            int n = SR * 950 / 1000;
            short[] o = new short[n];
            double phase = 0;
            for (int i = 0; i < n; i++) {
                double x = i / (double) n;
                double f = 90 + 2200 * x * x;
                phase += 2 * Math.PI * f / SR;
                double core = Math.sin(phase);
                double buzz = Math.signum(Math.sin(2 * Math.PI * 120 * i / SR));
                double crackle = RND.nextDouble() < 0.015 ? (RND.nextDouble() * 2 - 1) : 0;
                double env = Math.min(1.0, x * 25) * (x > 0.88 ? Math.max(0, (1 - x) / 0.12) : 1.0);
                double zap = x > 0.82 ? (RND.nextDouble() * 2 - 1) * 0.5 : 0;
                o[i] = clip((0.55 * core + 0.18 * buzz * x + 0.5 * crackle + zap) * env, 0.95);
            }
            soundSurge = o;
        }
        return soundSurge;
    }

    /** Electric power-release: initial zap, then a falling, decaying sweep. */
    private static synchronized short[] releaseSound() {
        if (soundRelease == null) {
            int n = SR * 1100 / 1000;
            short[] o = new short[n];
            double phase = 0;
            for (int i = 0; i < n; i++) {
                double x = i / (double) n;
                double f = 60 + 2000 * Math.exp(-4 * x);
                phase += 2 * Math.PI * f / SR;
                double core = Math.sin(phase);
                double buzz = Math.signum(Math.sin(2 * Math.PI * 100 * i / SR));
                double crackle = RND.nextDouble() < 0.02 * (1 - x) ? (RND.nextDouble() * 2 - 1) : 0;
                double zap = x < 0.06 ? (RND.nextDouble() * 2 - 1) * 0.7 : 0;
                double env = Math.min(1.0, x * 40) * Math.exp(-2.6 * x) * Math.min(1.0, (1 - x) * 20);
                o[i] = clip((0.6 * core + 0.2 * buzz * (1 - x) + 0.5 * crackle + zap) * env, 0.95);
            }
            soundRelease = o;
        }
        return soundRelease;
    }
}
