package com.omnidev.hardware;

import android.service.voice.AlwaysOnHotwordDetector;
import android.service.voice.VoiceInteractionService;
import java.util.Locale;
import java.util.function.Consumer;

/** Compiled by the manufacturer against system_current, never against the public APK SDK. */
@SuppressWarnings("deprecation")
public final class OmniHotwordAdapter implements AutoCloseable {
    private AlwaysOnHotwordDetector detector;
    private boolean enrolled;
    private boolean running;
    private boolean requested;
    private boolean closed;
    private final Consumer<String> status;

    public static OmniHotwordAdapter create(VoiceInteractionService service, String phrase,
            String locale, Runnable detected, Consumer<String> status) {
        return new OmniHotwordAdapter(service, phrase, locale, detected, status);
    }

    private OmniHotwordAdapter(VoiceInteractionService service, String phrase, String locale,
            Runnable detected, Consumer<String> status) {
        this.status = status;
        detector = service.createAlwaysOnHotwordDetector(phrase, Locale.forLanguageTag(locale),
                new AlwaysOnHotwordDetector.Callback() {
                    @Override public void onAvailabilityChanged(int state) {
                        if (closed) return;
                        enrolled = state == AlwaysOnHotwordDetector.STATE_KEYPHRASE_ENROLLED;
                        if (!enrolled) running = false;
                        status.accept(enrolled ? "Manufacturer wake model enrolled" :
                                "Manufacturer SoundTrigger model is unavailable or not enrolled: " + state);
                        if (requested && enrolled) startRecognition();
                    }
                    @Override public void onDetected(AlwaysOnHotwordDetector.EventPayload payload) {
                        if (!closed && running) { running = false; detected.run(); startRecognition(); }
                    }
                    @Override public void onError() { running = false; status.accept("Hardware wake detection failed"); }
                    @Override public void onRecognitionPaused() { status.accept("Hardware wake listening paused"); }
                    @Override public void onRecognitionResumed() { status.accept("Hardware wake listening resumed"); }
                });
    }

    public boolean startRecognition() {
        requested = !closed;
        if (closed || !enrolled || detector == null) return false;
        if (running) return true;
        running = detector.startRecognition(0);
        status.accept(running ? "Listening with manufacturer hardware" : "Hardware wake start rejected");
        return running;
    }
    public void stopRecognition() {
        requested = false;
        if (detector != null && running) detector.stopRecognition();
        running = false;
    }
    @Override public void close() {
        closed = true;
        stopRecognition();
        if (detector != null) detector.destroy();
        detector = null;
    }
}
