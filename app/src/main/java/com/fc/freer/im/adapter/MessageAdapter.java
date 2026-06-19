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
    }

    private final List<ImMessage> messages;
    private String liveFid;
    private ImType imType;
    private MessageInteractionListener listener;

    private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm", Locale.getDefault());

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

        holder.bind(message, isOutgoing, showSender, isP2P, listener);
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
                  MessageInteractionListener listener) {
            String senderId = message.getSenderId();
            boolean isSystemMessage = senderId == null || senderId.isEmpty();

            if (isSystemMessage) {
                incomingContainer.setVisibility(View.GONE);
                outgoingContainer.setVisibility(View.GONE);
                systemMessage.setVisibility(View.VISIBLE);
                systemMessage.setText(message.getContent() != null ? message.getContent() : "");
                return;
            }

            systemMessage.setVisibility(View.GONE);

            ContentType ct = message.getContentType();
            boolean isHat = ct == ContentType.HAT;
            boolean isStream = ct == ContentType.STREAM;
            boolean isVoice = ct == ContentType.VOICE;
            boolean isFileCard = isHat || isStream;

            String content = message.getContent();
            if (content == null) {
                content = message.getCipher() != null ? "[Encrypted]" : "";
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
                    bindVoiceCard(outgoingHatContainer, message, true);
                } else if (isFileCard) {
                    outgoingContent.setVisibility(View.GONE);
                    outgoingHatContainer.setVisibility(View.VISIBLE);
                    if (isHat) {
                        bindHatCard(outgoingHatContainer, content, true);
                    } else {
                        bindStreamCard(outgoingHatContainer, content, true);
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
                    bindVoiceCard(incomingHatContainer, message, false);
                } else if (isFileCard) {
                    incomingContent.setVisibility(View.GONE);
                    incomingHatContainer.setVisibility(View.VISIBLE);
                    if (isHat) {
                        bindHatCard(incomingHatContainer, content, false);
                    } else {
                        bindStreamCard(incomingHatContainer, content, false);
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
        }

        private void bindVoiceCard(FrameLayout container, ImMessage message, boolean isOutgoing) {
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
                    String dataBase64 = message.getDataBase64();
                    if (dataBase64 != null) {
                        byte[] audioData = Base64.decode(dataBase64, Base64.DEFAULT);
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

            container.addView(card);
        }

        private void bindHatCard(FrameLayout container, String hatJson, boolean isOutgoing) {
            container.removeAllViews();
            Context ctx = container.getContext();

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
                        ctx.getColor(R.color.white)));
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

            container.addView(card);
        }

        /**
         * Render a STREAM file card from metadata JSON: {"name":"...", "size":..., "type":"..."}
         */
        private void bindStreamCard(FrameLayout container, String metaJson, boolean isOutgoing) {
            container.removeAllViews();
            Context ctx = container.getContext();

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
                typeIcon.setImageResource(android.R.drawable.ic_media_play);
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
