package com.fc.freer.call;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;

/**
 * The device's audio routing for a call (VOICE_SPEC §9.2): communication
 * mode, which turns on the platform's echo canceller, transient audio focus,
 * and speaker or earpiece.
 */
public final class CallAudio {

    private final AudioManager audio;
    private AudioFocusRequest focus;
    private int previousMode;

    public CallAudio(Context context) {
        this.audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public synchronized void enter(boolean speaker) {
        if (focus != null) return;
        previousMode = audio.getMode();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .build();
        audio.requestAudioFocus(focus);
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        setSpeaker(speaker);
    }

    @SuppressWarnings("deprecation")
    public synchronized void setSpeaker(boolean on) {
        if (focus == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!on) {
                audio.clearCommunicationDevice();
                return;
            }
            for (AudioDeviceInfo d : audio.getAvailableCommunicationDevices()) {
                if (d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                    audio.setCommunicationDevice(d);
                    return;
                }
            }
        } else {
            audio.setSpeakerphoneOn(on);
        }
    }

    @SuppressWarnings("deprecation")
    public synchronized void leave() {
        if (focus == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice();
        else audio.setSpeakerphoneOn(false);
        audio.setMode(previousMode);
        audio.abandonAudioFocusRequest(focus);
        focus = null;
    }
}
