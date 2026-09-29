package com.fc.freer.call;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;

import com.fc.fc_ajdk.utils.TimberLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * The main process's end of the link to {@link VoiceService} (VOICE_SPEC
 * §11.3): it binds the {@code :voice} process, sends it commands, queued until
 * the bind completes, and hands its events to {@link CallManager} and
 * {@link MeetingManager} on the main thread.
 * <p>
 * If the voice process dies — a crash in libopus or the audio stack — the
 * running call or meeting is reported over, and the next one starts a fresh
 * voice process. The main process, and the identity in it, carry on.
 */
final class VoiceClient {

    private static final String TAG = "VoiceClient";

    interface Events {
        /** A {@link VoiceProtocol} event, on the main thread. */
        void onEvent(int what, Bundle data);

        /** The voice process died with this call or meeting running in it. */
        void onVoiceDied();
    }

    private static VoiceClient instance;

    static synchronized VoiceClient get(Context context) {
        if (instance == null) instance = new VoiceClient(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), this::onReply));
    private final List<Message> pending = new ArrayList<>();
    private Messenger service;
    private boolean binding;
    private Events calls, meetings;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = new Messenger(binder);
            Message register = Message.obtain(null, VoiceProtocol.REGISTER);
            register.replyTo = replies;
            deliver(register);
            List<Message> queued = new ArrayList<>(pending);
            pending.clear();
            for (Message m : queued) deliver(m);
            TimberLogger.i(TAG, "voice process connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // The voice process died. The binding stays: Android restarts it for the next call.
            TimberLogger.e(TAG, "the voice process died");
            service = null;
            if (calls != null) calls.onVoiceDied();
            if (meetings != null) meetings.onVoiceDied();
        }
    };

    private VoiceClient(Context context) {
        this.context = context;
    }

    void setCallEvents(Events e) {
        calls = e;
    }

    void setMeetingEvents(Events e) {
        meetings = e;
    }

    /** Start the voice process ahead of the first call, so answering does not wait for it. Main thread. */
    void bind() {
        if (service != null || binding) return;
        binding = context.bindService(new Intent(context, VoiceService.class), connection,
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        if (!binding) TimberLogger.e(TAG, "could not bind the voice service");
    }

    /** Send a command; queued until the voice process is connected. Main thread. */
    void send(int what, Bundle data) {
        Message m = Message.obtain(null, what);
        m.setData(data);
        if (service == null) {
            pending.add(m);
            bind();
            return;
        }
        deliver(m);
    }

    private void deliver(Message m) {
        try {
            service.send(m);
        } catch (RemoteException e) {
            TimberLogger.w(TAG, "command %d not delivered: %s", m.what, e.getMessage());
        }
    }

    private boolean onReply(Message msg) {
        Bundle data = msg.getData();
        int what = msg.what;
        Events target = what == VoiceProtocol.EVT_HANGUP_REQUEST ? null
                : what < VoiceProtocol.EVT_MEETING_JOINED ? calls : meetings;
        if (what == VoiceProtocol.EVT_HANGUP_REQUEST) {
            // The ongoing notification's button: whichever is running.
            MeetingManager mm = MeetingManager.getInstance(context);
            if (mm.isActive()) mm.leave();
            else CallManager.getInstance(context).hangup();
            return true;
        }
        if (target != null) {
            try {
                target.onEvent(what, data);
            } catch (RuntimeException e) {
                TimberLogger.e(TAG, "event %d: %s", what, e);
            }
        }
        return true;
    }
}
