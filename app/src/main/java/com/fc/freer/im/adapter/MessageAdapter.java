package com.fc.freer.im.adapter;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Base64;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fc.fc_ajdk.data.fcData.ContentType;
import com.fc.fc_ajdk.data.fcData.Hat;
import com.fc.fc_ajdk.data.fcData.ImMessage;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.fcData.MessageStatus;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.freer.R;
import com.fc.freer.im.FileShareHelper;
import com.fc.freer.im.ImManager;
import com.fc.freer.im.JoinTeamActivity;
import com.fc.freer.im.NobodyBoard;
import com.fc.freer.im.voice.VoiceMessageHelper;
import com.fc.freer.im.voice.VoicePlayer;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.utils.ApiCenter;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MessageViewHolder> {

    public interface MessageInteractionListener {
        void onSpeakerAvatarClick(String fid);
        void onSpeakerNameClick(String fid);
        void onSpeakerNameLongClick(String fid);
        void onOpenHat(Hat hat);
        void onDownloadHat(Hat hat);
        void onMessageLongPress(ImMessage message, View anchorView);
    }

    private final List<ImMessage> messages;
    private String liveFid;
    private ImType imType;
    private MessageInteractionListener listener;

    /** Live HAT download progress per hatId: [bytesReceived, totalBytes]. */
    private final java.util.Map<String, long[]> hatDownloads = new java.util.HashMap<>();

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault());

    public MessageAdapter(List<ImMessage> messages, String liveFid, ImType imType) {
        this.messages = messages;
        this.liveFid = liveFid;
        this.imType = imType;
    }

    public MessageAdapter(List<ImMessage> messages, String liveFid, ImType imType, MessageInteractionListener listener) {
        this.messages = messages;
        this.liveFid = liveFid;
        this.imType = imType;
        this.listener = listener;
    }

    public void setLiveFid(String liveFid) {
        this.liveFid = liveFid;
    }

    public void setImType(ImType imType) {
        this.imType = imType;
    }

    public void setListener(MessageInteractionListener listener) {
        this.listener = listener;
    }

    /**
     * Marks a HAT download active or updates its byte count. Monotonic: a lower byte
     * count than already recorded is ignored (receive progress may interleave with
     * smaller per-stream values).
     */
    public void setHatDownloadProgress(String hatId, long bytes, long total) {
        long[] cur = hatDownloads.get(hatId);
        if (cur == null) {
            hatDownloads.put(hatId, new long[]{bytes, total});
        } else {
            if (bytes > cur[0]) cur[0] = bytes;
            if (total > cur[1]) cur[1] = total;
        }
    }

    public void clearHatDownload(String hatId) {
        hatDownloads.remove(hatId);
    }

    /** Rebinds every message row that renders the given HAT. */
    public void notifyHatChanged(String hatId) {
        if (hatId == null) return;
        for (int i = 0; i < messages.size(); i++) {
            ImMessage m = messages.get(i);
            if (m.getContentType() == ContentType.HAT
                    && m.getContent() != null && m.getContent().contains(hatId)) {
                notifyItemChanged(i);
            }
        }
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        ImMessage message = messages.get(position);
        boolean isOutgoing = message.isOutgoing(liveFid);
        boolean isP2P = imType == ImType.P2P;

        String currentSender = message.getSenderId();
        String prevSender = position > 0 ? messages.get(position - 1).getSenderId() : null;
        boolean showSender = !isP2P && !isOutgoing && currentSender != null &&
                (position == 0 || !currentSender.equals(prevSender));

        holder.bind(message, isOutgoing, showSender, isP2P, listener, hatDownloads);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        private final LinearLayout incomingContainer;
        private final LinearLayout outgoingContainer;
        private final TextView systemMessage;
        private final ImageView senderAvatar;
        private final TextView senderName;
        private final TextView incomingContent;
        private final TextView incomingTime;
        private final TextView outgoingContent;
        private final TextView outgoingTime;
        private final ImageView statusIcon;
        private final FrameLayout incomingHatContainer;
        private final FrameLayout outgoingHatContainer;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            incomingContainer = itemView.findViewById(R.id.incoming_container);
            outgoingContainer = itemView.findViewById(R.id.outgoing_container);
            systemMessage = itemView.findViewById(R.id.system_message);
            senderAvatar = itemView.findViewById(R.id.sender_avatar);
            senderName = itemView.findViewById(R.id.sender_name);
            incomingContent = itemView.findViewById(R.id.incoming_content);
            incomingTime = itemView.findViewById(R.id.incoming_time);
            outgoingContent = itemView.findViewById(R.id.outgoing_content);
            outgoingTime = itemView.findViewById(R.id.outgoing_time);
            statusIcon = itemView.findViewById(R.id.status_icon);
            incomingHatContainer = itemView.findViewById(R.id.incoming_hat_container);
            outgoingHatContainer = itemView.findViewById(R.id.outgoing_hat_container);
        }

        void bind(ImMessage message, boolean isOutgoing, boolean showSender, boolean isP2P,
                  MessageInteractionListener listener, java.util.Map<String, long[]> hatDownloads) {
            String senderId = message.getSenderId();
            boolean isSystemMessage = senderId == null || senderId.isEmpty();

            if (isSystemMessage) {
                incomingContainer.setVisibility(View.GONE);
                outgoingContainer.setVisibility(View.GONE);
                systemMessage.setVisibility(View.VISIBLE);
                systemMessage.setText(message.getContent() != null ? message.getContent() : "");
                return;
            }

            String content = message.getContent();

            // Content authored by a nobody FID (public private key — anyone can
            // have written it) must never render as actionable.
            boolean senderIsNobody = !isOutgoing && NobodyBoard.isKnownNobody(senderId);

            // Team invite / transfer notifications render as tappable system messages
            if (content != null && (content.startsWith(ImManager.TEAM_INVITE_PREFIX)
                    || content.startsWith(ImManager.TEAM_TRANSFER_PREFIX))) {
                if (senderIsNobody) {
                    incomingContainer.setVisibility(View.GONE);
                    outgoingContainer.setVisibility(View.GONE);
                    systemMessage.setVisibility(View.VISIBLE);
                    systemMessage.setText(itemView.getContext().getString(R.string.nobody_content_hidden));
                    systemMessage.setClickable(false);
                    systemMessage.setOnClickListener(null);
                    systemMessage.setBackground(null);
                } else {
                    bindTeamNotification(content);
                }
                return;
            }

            systemMessage.setVisibility(View.GONE);

            ContentType ct = message.getContentType();
            boolean isHat = ct == ContentType.HAT;
            boolean isStream = ct == ContentType.STREAM;
            boolean isVoice = ct == ContentType.VOICE;
            boolean isFileCard = isHat || isStream;

            // Interactive cards (files, streams, voice) from a nobody are
            // replaced by an inert placeholder.
            if (senderIsNobody && (isHat || isStream || isVoice)) {
                isHat = false;
                isStream = false;
                isVoice = false;
                isFileCard = false;
                content = itemView.getContext().getString(R.string.nobody_content_hidden);
            }

            if (content == null) {
                content = message.isSealed() ? "[Encrypted]" : "";
            }

            String time = message.getTimestamp() != null
                    ? TIME_FORMAT.format(new Date(message.getTimestamp()))
                    : "";

            if (isOutgoing) {
                incomingContainer.setVisibility(View.GONE);
                outgoingContainer.setVisibility(View.VISIBLE);

                if (isVoice) {
                    outgoingContent.setVisibility(View.GONE);
                    outgoingHatContainer.setVisibility(View.VISIBLE);
                    bindVoiceCard(outgoingHatContainer, message, true, listener);
                } else if (isFileCard) {
                    outgoingContent.setVisibility(View.GONE);
                    outgoingHatContainer.setVisibility(View.VISIBLE);
                    if (isHat) {
                        bindHatCard(outgoingHatContainer, message, true, listener, hatDownloads);
                    } else {
                        bindStreamCard(outgoingHatContainer, message, true, listener);
                    }
                } else {
                    outgoingContent.setVisibility(View.VISIBLE);
                    outgoingHatContainer.setVisibility(View.GONE);
                    outgoingContent.setText(content);
                }

                outgoingTime.setText(time);

                MessageStatus status = message.getStatus();
                if (status != null) {
                    statusIcon.setVisibility(View.VISIBLE);
                    switch (status) {
                        case PENDING -> {
                            statusIcon.setImageResource(R.drawable.ic_pending);
                            statusIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                                    itemView.getContext().getColor(R.color.hint)));
                        }
                        case SENT, DELIVERED, READ -> {
                            statusIcon.setImageResource(status == MessageStatus.READ ? R.drawable.ic_double_ticks
                                    : status == MessageStatus.DELIVERED ? R.drawable.ic_check : R.drawable.ic_send);
                            statusIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                                    itemView.getContext().getColor(R.color.hint)));
                        }
                        case FAILED -> {
                            statusIcon.setImageResource(R.drawable.ic_error);
                            statusIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                                    itemView.getContext().getColor(R.color.red)));
                        }
                        default -> statusIcon.setVisibility(View.GONE);
                    }
                } else {
                    statusIcon.setVisibility(View.GONE);
                }
            } else {
                outgoingContainer.setVisibility(View.GONE);
                incomingContainer.setVisibility(View.VISIBLE);

                if (isVoice) {
                    incomingContent.setVisibility(View.GONE);
                    incomingHatContainer.setVisibility(View.VISIBLE);
                    bindVoiceCard(incomingHatContainer, message, false, listener);
                } else if (isFileCard) {
                    incomingContent.setVisibility(View.GONE);
                    incomingHatContainer.setVisibility(View.VISIBLE);
                    if (isHat) {
                        bindHatCard(incomingHatContainer, message, false, listener, hatDownloads);
                    } else {
                        bindStreamCard(incomingHatContainer, message, false, listener);
                    }
                } else {
                    incomingContent.setVisibility(View.VISIBLE);
                    incomingHatContainer.setVisibility(View.GONE);
                    incomingContent.setText(content);
                }

                incomingTime.setText(time);

                if (isP2P) {
                    senderAvatar.setVisibility(View.GONE);
                    senderName.setVisibility(View.GONE);
                } else {
                    senderAvatar.setVisibility(View.VISIBLE);

                    if (showSender && senderId != null) {
                        String displayId = truncateFid(senderId);
                        senderName.setText(displayId);
                        senderName.setVisibility(View.VISIBLE);
                    } else {
                        senderName.setVisibility(View.GONE);
                    }

                    loadSenderInfo(senderId, showSender);

                    if (listener != null && senderId != null) {
                        senderAvatar.setOnClickListener(v -> listener.onSpeakerAvatarClick(senderId));

                        senderName.setOnClickListener(v -> listener.onSpeakerNameClick(senderId));
                        senderName.setOnLongClickListener(v -> {
                            listener.onSpeakerNameLongClick(senderId);
                            return true;
                        });
                    }
                }
            }

            if (listener != null) {
                View bubble = isOutgoing ? outgoingContainer : incomingContainer;
                bubble.setOnLongClickListener(v -> {
                    listener.onMessageLongPress(message, v);
                    return true;
                });
            }
        }

        private void bindTeamNotification(String content) {
            incomingContainer.setVisibility(View.GONE);
            outgoingContainer.setVisibility(View.GONE);
            systemMessage.setVisibility(View.VISIBLE);

            boolean isInvite = content.startsWith(ImManager.TEAM_INVITE_PREFIX);
            String rest = content.substring(isInvite
                    ? ImManager.TEAM_INVITE_PREFIX.length()
                    : ImManager.TEAM_TRANSFER_PREFIX.length());
            int pipe = rest.indexOf('|');
            String teamName = (pipe > 0) ? rest.substring(pipe + 1) : rest;
            if (teamName.isEmpty()) teamName = (pipe > 0) ? rest.substring(0, pipe) : rest;

            Context ctx = itemView.getContext();
            String text = isInvite
                    ? ctx.getString(R.string.team_invite_system_message, teamName)
                    : ctx.getString(R.string.team_transfer_system_message, teamName);
            systemMessage.setText(text);
            systemMessage.setClickable(true);
            android.util.TypedValue tv = new android.util.TypedValue();
            ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            systemMessage.setBackgroundResource(tv.resourceId);
            systemMessage.setOnClickListener(v ->
                    ctx.startActivity(new Intent(ctx, JoinTeamActivity.class)));
        }

        private void bindVoiceCard(FrameLayout container, ImMessage message, boolean isOutgoing,
                                   MessageInteractionListener listener) {
            container.removeAllViews();
            Context ctx = container.getContext();
            View card = LayoutInflater.from(ctx).inflate(R.layout.item_im_voice_card, container, false);

            ImageButton playBtn = card.findViewById(R.id.voice_play_btn);
            SeekBar seekbar = card.findViewById(R.id.voice_seekbar);
            TextView durationView = card.findViewById(R.id.voice_duration);

            long durationMs = VoiceMessageHelper.parseDurationMs(message.getContent());
            durationView.setText(VoiceMessageHelper.formatDuration(durationMs));

            String messageId = message.getId();
            VoicePlayer player = VoicePlayer.getInstance();

            // Set initial state based on whether this message is currently playing
            if (player.isPlaying(messageId)) {
                playBtn.setImageResource(R.drawable.ic_pause);
                int total = player.getDuration();
                if (total > 0) {
                    seekbar.setProgress(player.getCurrentPosition() * 100 / total);
                }
            } else {
                playBtn.setImageResource(R.drawable.ic_play);
                seekbar.setProgress(0);
            }

            playBtn.setOnClickListener(v -> {
                if (player.isPlaying(messageId)) {
                    player.pause();
                    playBtn.setImageResource(R.drawable.ic_play);
                } else if (player.isPaused(messageId)) {
                    player.resume();
                    playBtn.setImageResource(R.drawable.ic_pause);
                } else {
                    byte[] audioData = message.getData();
                    if (audioData != null) {
                        player.setListener(new VoicePlayer.PlaybackListener() {
                            @Override
                            public void onProgress(String msgId, int currentMs, int totalMs) {
                                if (msgId != null && msgId.equals(messageId) && totalMs > 0) {
                                    seekbar.setProgress(currentMs * 100 / totalMs);
                                    durationView.setText(VoiceMessageHelper.formatDuration(totalMs - currentMs));
                                }
                            }

                            @Override
                            public void onComplete(String msgId) {
                                playBtn.setImageResource(R.drawable.ic_play);
                                seekbar.setProgress(0);
                                durationView.setText(VoiceMessageHelper.formatDuration(durationMs));
                            }

                            @Override
                            public void onError(String msgId) {
                                playBtn.setImageResource(R.drawable.ic_play);
                                seekbar.setProgress(0);
                            }
                        });
                        player.play(messageId, audioData);
                        playBtn.setImageResource(R.drawable.ic_pause);
                    }
                }
            });

            seekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && (player.isPlaying(messageId) || player.isPaused(messageId))) {
                        int total = player.getDuration();
                        if (total > 0) {
                            player.seekTo(progress * total / 100);
                        }
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            if (listener != null) {
                card.setOnLongClickListener(v -> {
                    listener.onMessageLongPress(message, v);
                    return true;
                });
            }

            container.addView(card);
        }

        private void bindHatCard(FrameLayout container, ImMessage message, boolean isOutgoing,
                                 MessageInteractionListener listener,
                                 java.util.Map<String, long[]> hatDownloads) {
            container.removeAllViews();
            Context ctx = container.getContext();

            String hatJson = message.getContent();
            Hat hat = null;
            try {
                hat = Hat.fromJson(hatJson, Hat.class);
            } catch (Exception ignored) {}

            if (hat == null) {
                TextView fallback = new TextView(ctx);
                fallback.setText("[HAT]");
                fallback.setPadding(16, 8, 16, 8);
                container.addView(fallback);
                return;
            }

            View card = LayoutInflater.from(ctx).inflate(R.layout.item_im_hat_card, container, false);

            ImageView typeIcon = card.findViewById(R.id.im_hat_type_icon);
            TextView nameView = card.findViewById(R.id.im_hat_card_name);
            TextView sizeView = card.findViewById(R.id.im_hat_card_size);
            TextView descView = card.findViewById(R.id.im_hat_card_desc);

            typeIcon.setImageResource(FileShareHelper.getTypeIconRes(hat));
            if (isOutgoing) {
                typeIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                        ctx.getColor(R.color.field_name)));
                nameView.setTextColor(ctx.getColor(R.color.white));
                sizeView.setTextColor(ctx.getColor(R.color.white_transparent));
                descView.setTextColor(ctx.getColor(R.color.white_transparent));
            }

            String name = hat.getName();
            nameView.setText(name != null ? name : ctx.getString(R.string.unnamed));

            Long size = hat.getSize();
            if (size != null) {
                sizeView.setText(FileShareHelper.formatSize(size));
            }

            String desc = hat.getDesc();
            if (desc != null && !desc.isEmpty()) {
                descView.setText(desc);
                descView.setVisibility(View.VISIBLE);
            } else {
                descView.setVisibility(View.GONE);
            }

            Hat finalHat = hat;
            card.setOnClickListener(v -> openHatDetail(ctx, finalHat));

            ImageButton playBtn = card.findViewById(R.id.im_hat_play_btn);
            ImageButton downloadBtn = card.findViewById(R.id.im_hat_download_btn);
            int accentColor = ctx.getColor(isOutgoing ? R.color.white : R.color.accent);
            playBtn.setImageTintList(android.content.res.ColorStateList.valueOf(accentColor));
            downloadBtn.setImageTintList(android.content.res.ColorStateList.valueOf(accentColor));
            playBtn.setOnClickListener(v -> {
                if (listener != null) listener.onOpenHat(finalHat);
            });
            downloadBtn.setOnClickListener(v -> {
                if (listener != null) listener.onDownloadHat(finalHat);
            });

            com.google.android.material.progressindicator.CircularProgressIndicator downloadProgress =
                    card.findViewById(R.id.im_hat_download_progress);
            TextView percentView = card.findViewById(R.id.im_hat_download_percent);
            long[] dl = hatDownloads != null ? hatDownloads.get(hat.getId()) : null;
            if (dl != null) {
                playBtn.setVisibility(View.INVISIBLE);
                downloadBtn.setVisibility(View.INVISIBLE);
                downloadProgress.setIndicatorColor(accentColor);
                percentView.setTextColor(accentColor);
                long total = dl[1];
                if (total > 0) {
                    // Received bytes include protocol framing overhead; clamp below 100%
                    // so the ring only completes when the download actually finishes.
                    int percent = (int) Math.min(99, dl[0] * 100 / total);
                    downloadProgress.setIndeterminate(false);
                    downloadProgress.setProgress(percent);
                    percentView.setText(percent + "%");
                    percentView.setVisibility(View.VISIBLE);
                } else {
                    downloadProgress.setIndeterminate(true);
                    percentView.setVisibility(View.GONE);
                }
                downloadProgress.setVisibility(View.VISIBLE);
                // Tapping the ring asks whether to cancel (handled by the listener,
                // which knows this HAT is downloading).
                downloadProgress.setOnClickListener(v -> {
                    if (listener != null) listener.onOpenHat(finalHat);
                });
                percentView.setOnClickListener(v -> {
                    if (listener != null) listener.onOpenHat(finalHat);
                });
            } else {
                downloadProgress.setVisibility(View.GONE);
                percentView.setVisibility(View.GONE);
                playBtn.setVisibility(View.VISIBLE);
                downloadBtn.setVisibility(View.VISIBLE);
            }

            if (listener != null) {
                card.setOnLongClickListener(v -> {
                    listener.onMessageLongPress(message, v);
                    return true;
                });
            }

            container.addView(card);
        }

        /**
         * Render a STREAM file card from metadata JSON: {"name":"...", "size":..., "type":"..."}
         */
        private void bindStreamCard(FrameLayout container, ImMessage message, boolean isOutgoing,
                                    MessageInteractionListener listener) {
            container.removeAllViews();
            Context ctx = container.getContext();

            String metaJson = message.getContent();
            String fileName = null;
            long fileSize = 0;
            String mimeType = null;
            try {
                org.json.JSONObject obj = new org.json.JSONObject(metaJson);
                fileName = obj.optString("name", null);
                fileSize = obj.optLong("size", 0);
                mimeType = obj.optString("type", null);
            } catch (Exception ignored) {}

            View card = LayoutInflater.from(ctx).inflate(R.layout.item_im_hat_card, container, false);

            ImageView typeIcon = card.findViewById(R.id.im_hat_type_icon);
            TextView nameView = card.findViewById(R.id.im_hat_card_name);
            TextView sizeView = card.findViewById(R.id.im_hat_card_size);
            TextView descView = card.findViewById(R.id.im_hat_card_desc);

            if (mimeType != null && mimeType.startsWith("image/")) {
                typeIcon.setImageResource(android.R.drawable.ic_menu_gallery);
            } else if (mimeType != null && mimeType.startsWith("audio/")) {
                typeIcon.setImageResource(android.R.drawable.ic_lock_silent_mode_off);
            } else if (mimeType != null && mimeType.startsWith("video/")) {
                typeIcon.setImageResource(R.drawable.ic_video_file);
            } else {
                typeIcon.setImageResource(R.drawable.ic_file);
            }

            if (isOutgoing) {
                typeIcon.setImageTintList(android.content.res.ColorStateList.valueOf(
                        ctx.getColor(R.color.white)));
                nameView.setTextColor(ctx.getColor(R.color.white));
                sizeView.setTextColor(ctx.getColor(R.color.white_transparent));
            }

            nameView.setText(fileName != null ? fileName : ctx.getString(R.string.unnamed));
            if (fileSize > 0) {
                sizeView.setText(FileShareHelper.formatSize(fileSize));
            }
            descView.setVisibility(View.GONE);

            if (listener != null) {
                card.setOnLongClickListener(v -> {
                    listener.onMessageLongPress(message, v);
                    return true;
                });
            }

            container.addView(card);
        }

        private void openHatDetail(Context ctx, Hat hat) {
            com.fc.freer.manager.FidManager fidManager = com.fc.freer.manager.FidManager.getInstance();
            String liveFid = fidManager != null ? fidManager.getLiveFid() : null;
            if (liveFid == null) return;

            com.fc.freer.manager.HatManager hm = com.fc.freer.manager.HatManager.getInstance(ctx, liveFid);
            if (hm.getHatById(hat.getId()) == null) {
                hm.addHat(hat);
                hm.commit();
            }

            Intent intent = new Intent(ctx, com.fc.freer.data.HatDetailActivity.class);
            intent.putExtra(com.fc.freer.data.HatDetailActivity.EXTRA_HAT_ID, hat.getId());
            ctx.startActivity(intent);
        }

        private static final Set<String> noCidFids = Collections.synchronizedSet(new HashSet<>());

        private void loadSenderInfo(String fid, boolean resolveName) {
            if (fid == null) return;
            Context context = itemView.getContext();
            senderAvatar.setImageResource(R.drawable.ic_person);
            senderAvatar.setTag(fid);

            new Thread(() -> {
                try {
                    AvatarManager avatarManager = AvatarManager.getInstance(context);
                    Bitmap bitmap = avatarManager.getAvatarBitmap(fid);

                    String cid = null;
                    if (resolveName) {
                        cid = resolveCid(fid);
                    }

                    final Bitmap finalBitmap = bitmap;
                    final String finalCid = cid;

                    senderAvatar.post(() -> {
                        if (!fid.equals(senderAvatar.getTag())) return;

                        if (finalBitmap != null) {
                            senderAvatar.setImageBitmap(finalBitmap);
                            senderAvatar.setImageTintList(null);
                            senderAvatar.setPadding(0, 0, 0, 0);
                        }
                        if (NobodyBoard.isKnownNobody(fid)) {
                            NobodyBoard.applyNobodyMark(senderAvatar);
                        } else {
                            NobodyBoard.clearNobodyMark(senderAvatar);
                        }
                        if (resolveName && finalCid != null && !finalCid.isEmpty()) {
                            senderName.setText(finalCid);
                        }
                    });
                } catch (Exception ignored) {
                }
            }).start();
        }

        private static String resolveCid(String fid) {
            CidFidManager cidFidManager = CidFidManager.getInstance();
            if (cidFidManager != null) {
                String cid = cidFidManager.getCidByFid(fid);
                if (cid != null && !cid.isEmpty()) {
                    return cid;
                }
            }

            if (noCidFids.contains(fid)) return null;

            try {
                ApiCenter apiCenter = ApiCenter.getInstance();
                if (apiCenter == null) return null;

                FapiClient fapiClient = (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient == null) return null;

                Freer freer = fapiClient.getFreer(fid);
                if (freer != null && Boolean.TRUE.equals(freer.getNobody())) {
                    NobodyBoard.markNobody(fid);
                }
                if (freer != null && freer.getCid() != null && !freer.getCid().isEmpty()) {
                    if (cidFidManager != null) {
                        cidFidManager.add(fid, freer.getCid());
                    }
                    return freer.getCid();
                } else {
                    noCidFids.add(fid);
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        private static String truncateFid(String fid) {
            return fid.length() > 12
                    ? fid.substring(0, 6) + "..." + fid.substring(fid.length() - 4)
                    : fid;
        }
    }
}
