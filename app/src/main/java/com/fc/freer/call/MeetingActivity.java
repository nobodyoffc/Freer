package com.fc.freer.call;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.freer.R;
import com.fc.freer.im.ImManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.nobody.NobodyUi;
import com.fc.freer.utils.ToolbarUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The in-meeting screen (VOICE_SPEC §10): who is here, who is speaking, raised
 * hands, and, for the host, the controls of §7.2 on a participant's long
 * press. It always says the meeting is end-to-end encrypted and which relay
 * carries it, and warns when someone's audio could not be verified (§5.1).
 * All state lives in {@link MeetingManager}; this only shows it.
 */
public class MeetingActivity extends AppCompatActivity implements MeetingManager.Listener {

    private static final long CLOSE_AFTER_END_MS = 2_000;

    private MeetingManager meetings;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::render;
    private TextView title, status, path, warning;
    private CheckBox mute, speaker, hand;
    private Button leave, endForAll, join, decline;
    private TextView invite;
    private View toggles;
    private final ParticipantAdapter adapter = new ParticipantAdapter();
    /** This screen opened for a ring: once the ring stops unanswered, there is nothing to show. */
    private boolean shownRinging;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A ringing meeting shows over the lock screen, as a call does.
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        setContentView(R.layout.activity_meeting);
        ToolbarUtils.setupToolbar(this, getString(R.string.meeting_ongoing_title));
        meetings = MeetingManager.getInstance(this);

        title = findViewById(R.id.meetingTitle);
        status = findViewById(R.id.meetingStatus);
        path = findViewById(R.id.meetingPath);
        warning = findViewById(R.id.meetingWarning);
        mute = findViewById(R.id.meetingMute);
        speaker = findViewById(R.id.meetingSpeaker);
        hand = findViewById(R.id.meetingHand);
        leave = findViewById(R.id.meetingLeave);
        endForAll = findViewById(R.id.meetingEndForAll);
        join = findViewById(R.id.meetingJoin);
        decline = findViewById(R.id.meetingDecline);
        join.setOnClickListener(v -> meetings.answerRing(error -> {
            if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        }));
        decline.setOnClickListener(v -> {
            meetings.declineRing();
            finish();
        });
        toggles = findViewById(R.id.meetingToggles);
        invite = findViewById(R.id.meetingInvite);
        invite.setOnClickListener(v -> {
            MeetingBoard.Meeting m = meetings.meeting();
            if (m == null) return;
            java.util.Set<String> except = new java.util.HashSet<>(m.invitees);
            except.add(meetings.myFid());
            MemberPicker.show(this, MemberPicker.members(m.entityType, m.entityId, except), meetings::invite);
        });
        RecyclerView list = findViewById(R.id.meetingParticipants);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        mute.setOnClickListener(v -> meetings.setMuted(mute.isChecked()));
        speaker.setOnClickListener(v -> meetings.setSpeaker(speaker.isChecked()));
        hand.setOnClickListener(v -> meetings.setHand(hand.isChecked()));
        leave.setOnClickListener(v -> {
            if (meetings.isActive()) meetings.leave();
            else finish();
        });
        endForAll.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setMessage(R.string.meeting_end_confirm)
                .setPositiveButton(R.string.meeting_end_for_all, (d, w) -> meetings.endForAll())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    @Override
    protected void onStart() {
        super.onStart();
        meetings.addListener(this);
        render();
    }

    @Override
    protected void onStop() {
        meetings.removeListener(this);
        handler.removeCallbacks(tick);
        super.onStop();
    }

    @Override
    public void onMeetingChanged() {
        render();
    }

    private void render() {
        handler.removeCallbacks(tick);
        MeetingBoard.Meeting ringing = meetings.isActive() ? null : meetings.ringing();
        join.setVisibility(ringing != null ? View.VISIBLE : View.GONE);
        decline.setVisibility(ringing != null ? View.VISIBLE : View.GONE);
        if (ringing != null) {
            // Ringing: who started what, and Join or Decline; nothing else yet.
            title.setText(R.string.meeting_ringing_title);
            status.setText(meetings.ringText(ringing));
            path.setText("");
            warning.setVisibility(View.GONE);
            toggles.setVisibility(View.GONE);
            endForAll.setVisibility(View.GONE);
            invite.setVisibility(View.GONE);
            leave.setVisibility(View.GONE);
            adapter.show(null, List.of());
            shownRinging = true;
            return;
        }
        if (shownRinging && !meetings.isActive()) {
            finish(); // declined elsewhere, timed out, or the meeting ended while ringing
            return;
        }
        shownRinging = false;
        leave.setVisibility(View.VISIBLE);
        MeetingManager.Phase phase = meetings.phase();
        MeetingSession s = meetings.session();
        String t = meetings.title();
        title.setText(t == null ? "" : t);

        List<MeetingSession.Participant> people = s == null ? List.of() : s.participants();
        switch (phase) {
            case CONNECTING -> status.setText(R.string.meeting_connecting);
            case IN_MEETING -> {
                long since = s == null ? -1 : s.joinedAtMs();
                String clock = since > 0 ? CallActivity.duration(System.currentTimeMillis() - since) : "";
                status.setText(clock + " · " + getString(R.string.meeting_participants, people.size()));
                handler.postDelayed(tick, 1_000);
            }
            case ENDED, IDLE -> status.setText(meetings.endReason() == null ? "" : meetings.endReason());
        }

        String relay = meetings.relayHost();
        long rtt = s == null ? -1 : s.rttMs();
        path.setText(relay == null ? "" : rtt >= 0 ? getString(R.string.call_relayed_via_rtt, relay, rtt)
                : getString(R.string.call_relayed_via, relay));

        List<String> unverified = s == null ? List.of() : s.unverifiedFids();
        warning.setVisibility(unverified.isEmpty() ? View.GONE : View.VISIBLE);
        if (!unverified.isEmpty()) warning.setText(getString(R.string.meeting_unverified_warning, displayName(unverified.get(0))));

        boolean live = meetings.isActive();
        toggles.setVisibility(live ? View.VISIBLE : View.GONE);
        mute.setChecked(s != null && s.isMuted());
        // A locked mute is the host's to lift (§7.2).
        mute.setEnabled(s == null || !"locked".equals(s.mutedByHost()));
        speaker.setChecked(meetings.isSpeaker());
        hand.setChecked(s != null && s.handRaised());
        endForAll.setVisibility(live && s != null && s.isHost() ? View.VISIBLE : View.GONE);
        MeetingBoard.Meeting meeting = meetings.meeting();
        // Only chosen people hold its key: the host brings in more by inviting them (Decision 20).
        invite.setVisibility(live && s != null && s.isHost() && meeting != null && meeting.invited ? View.VISIBLE : View.GONE);
        leave.setText(live ? R.string.meeting_leave : R.string.call_close);

        adapter.show(s, people);
        if (!live && phase == MeetingManager.Phase.ENDED) handler.postDelayed(this::finish, CLOSE_AFTER_END_MS);
    }

    /** The host's controls for one participant (§7.2). */
    private void showControls(MeetingSession.Participant p) {
        MeetingSession s = meetings.session();
        if (s == null || !s.isHost() || p.me()) return;
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (p.mutedByHost() == null) {
            labels.add(getString(R.string.meeting_action_mute));
            actions.add(() -> control("mute", p.ssrc()));
            labels.add(getString(R.string.meeting_action_lock_mute));
            actions.add(() -> control("lockMute", p.ssrc()));
        } else {
            labels.add(getString(R.string.meeting_action_unmute));
            actions.add(() -> control("unmute", p.ssrc()));
        }
        labels.add(getString(R.string.meeting_action_pin));
        actions.add(() -> control("pin", p.ssrc()));
        labels.add(getString(R.string.meeting_action_unpin));
        actions.add(() -> control("unpin", p.ssrc()));
        labels.add(getString(R.string.meeting_action_make_host));
        actions.add(() -> control("handoverHost", p.fid()));
        labels.add(getString(R.string.meeting_action_kick));
        actions.add(() -> new AlertDialog.Builder(this)
                .setMessage(getString(R.string.meeting_kick_confirm, displayName(p.fid())))
                .setPositiveButton(R.string.meeting_action_kick, (d, w) -> control("kick", p.fid()))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
        new AlertDialog.Builder(this)
                .setTitle(displayName(p.fid()))
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void control(String action, Object target) {
        meetings.control(action, target, error -> {
            if (error != null) Toast.makeText(this, getString(R.string.meeting_control_failed, error), Toast.LENGTH_LONG).show();
        });
    }

    private String displayName(String fid) {
        ImManager im = FidManager.getInstance().getImManager();
        var p = im == null ? null : im.getTalkPartner(fid);
        String cid = p == null ? null : p.getCid();
        return cid != null && !cid.isEmpty() ? cid : StringUtils.omitMiddle(fid, 20);
    }

    private final class ParticipantAdapter extends RecyclerView.Adapter<ParticipantAdapter.Row> {
        private List<MeetingSession.Participant> rows = List.of();
        private Set<Integer> speaking = Set.of(), paused = Set.of();

        void show(MeetingSession s, List<MeetingSession.Participant> people) {
            rows = people;
            speaking = s == null ? Set.of() : s.speaking();
            paused = s == null ? Set.of() : s.pausedSsrcs();
            List<String> fids = new ArrayList<>();
            for (MeetingSession.Participant p : people) fids.add(p.fid());
            NobodyUi.resolveAsync(fids);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Row(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_meeting_participant, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Row h, int position) {
            MeetingSession.Participant p = rows.get(position);
            String name = displayName(p.fid()) + (p.me() ? " " + getString(R.string.meeting_you) : "");
            NobodyUi.setName(h.name, p.fid(), name);
            boolean talking = speaking.contains(p.ssrc());
            h.name.setTypeface(null, talking ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            List<String> state = new ArrayList<>();
            if (p.hand()) state.add("✋");
            if (p.host()) state.add(getString(R.string.meeting_host));
            if (talking) state.add(getString(R.string.meeting_speaking));
            if ("locked".equals(p.mutedByHost())) state.add(getString(R.string.meeting_mute_locked));
            else if (p.mutedByHost() != null) state.add(getString(R.string.meeting_muted_by_host));
            if (!p.verified()) state.add(getString(R.string.meeting_not_verified));
            else if (paused.contains(p.ssrc())) state.add(getString(R.string.meeting_audio_paused));
            h.state.setText(String.join(" · ", state));
            h.itemView.setOnLongClickListener(v -> {
                showControls(p);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        final class Row extends RecyclerView.ViewHolder {
            final TextView name, state;

            Row(View v) {
                super(v);
                name = v.findViewById(R.id.participantName);
                state = v.findViewById(R.id.participantState);
            }
        }
    }
}
