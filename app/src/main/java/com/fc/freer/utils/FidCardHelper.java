package com.fc.freer.utils;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.feature.avatar.AvatarMaker;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;

/**
 * Utility class for creating and configuring FID cards using the reusable layout
 */
public class FidCardHelper {

    /**
     * Creates and configures a FID card view using the reusable layout
     * @param context The context to use for inflating the layout
     * @param keyInfo The KeyInfo object to display
     * @param parent The parent view to add the card to (can be null if you want to handle adding manually)
     * @param enableClickListeners Whether to enable click listeners for copying and avatar display
     * @param clickListener Optional click listener for handling custom clicks (can be null)
     * @return The configured card view
     */
    public static View createFidCard(Context context, KeyInfo keyInfo, ViewGroup parent, boolean enableClickListeners, FidCardClickListener clickListener) {
        if (keyInfo == null || keyInfo.getId() == null || keyInfo.getId().isEmpty()) {
            return null;
        }

        View cardView = LayoutInflater.from(context).inflate(R.layout.layout_fid_card, parent, false);
        setupFidCardView(context, cardView, keyInfo, enableClickListeners, clickListener);
        
        if (parent != null) {
            parent.addView(cardView);
        }
        
        return cardView;
    }

    /**
     * Sets up an existing FID card view with KeyInfo data
     * @param context The context to use
     * @param cardView The card view to configure (should use layout_fid_card)
     * @param keyInfo The KeyInfo object to display
     * @param enableClickListeners Whether to enable click listeners for copying and avatar display
     * @param clickListener Optional click listener for handling custom clicks (can be null)
     */
    public static void setupFidCardView(Context context, View cardView, KeyInfo keyInfo, boolean enableClickListeners, FidCardClickListener clickListener) {
        if (cardView == null || keyInfo == null) return;

        ImageView avatar = cardView.findViewById(R.id.fidAvatar);
        TextView nameTextView = cardView.findViewById(R.id.fidName);
        TextView labelTextView = cardView.findViewById(R.id.fidLabel);
        ImageView editIconView = cardView.findViewById(R.id.fidEditIcon);
        TextView cashTextView = cardView.findViewById(R.id.fidCash);
        TextView balanceTextView = cardView.findViewById(R.id.fidBalance);
        TextView cdTextView = cardView.findViewById(R.id.fidCd);
        ImageView noPrikeyIconView = cardView.findViewById(R.id.multisigIcon);

        // Set avatar (grayscale marks a nobody FID: leaked/public prikey)
        if (avatar != null) {
            try {
                byte[] avatarBytes = AvatarMaker.createAvatar(keyInfo.getId(), context);
                if (avatarBytes != null) {
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length);
                    avatar.setImageBitmap(bitmap);
                } else {
                    avatar.setImageResource(R.drawable.ic_person);
                }
            } catch (Exception e) {
                avatar.setImageResource(R.drawable.ic_person);
            }
            if (Boolean.TRUE.equals(keyInfo.getIsNobody())) {
                com.fc.freer.im.NobodyBoard.applyNobodyMark(avatar);
            } else {
                com.fc.freer.im.NobodyBoard.clearNobodyMark(avatar);
            }
        }

        // Set name (cid if available, otherwise fid)
        if (nameTextView != null) {
            String displayName = keyInfo.getCid();
            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = keyInfo.getId();
            }
            nameTextView.setText(displayName);
        }

        // Set label or edit icon
        if (labelTextView != null && editIconView != null) {
            String label = keyInfo.getLabel();
            if (label != null && !label.trim().isEmpty()) {
                // Show label, hide edit icon
                labelTextView.setText(label);
                labelTextView.setVisibility(View.VISIBLE);
                editIconView.setVisibility(View.GONE);
            } else {
                // Show edit icon, hide label
                labelTextView.setVisibility(View.GONE);
                editIconView.setVisibility(View.VISIBLE);
            }
        }

        // Set cash amount (handle null case)
        if (cashTextView != null) {
            Long cash = keyInfo.getCash();
            if (cash != null && cash > 0) {
                cashTextView.setText(String.valueOf(cash));
            } else {
                cashTextView.setText("-");
            }
        }

        // Set balance (handle null case)
        if (balanceTextView != null) {
            Long balance = keyInfo.getBalance();
            if (balance != null && balance > 0) {
                double balanceInCoins = FchUtils.satoshiToCoin(balance);
                balanceTextView.setText(formatBalance(balanceInCoins));
            } else {
                balanceTextView.setText("-");
            }
        }

        // Set cd (confirmation depth, handle null case)
        if (cdTextView != null) {
            Long cd = keyInfo.getCd();
            if (cd != null && cd > 0) {
                cdTextView.setText(formatLargeNumber(cd));
            } else {
                cdTextView.setText("-");
            }
        }

        // Show/hide no_prikey icon or people icon based on FID type and prikeyCipher
        if (noPrikeyIconView != null) {
            String prikeyCipher = keyInfo.getPrikeyCipher();
            if (prikeyCipher == null || prikeyCipher.trim().isEmpty()) {
                String fidId = keyInfo.getId();
                // Check if fidId is a multisig FID (starts with '3')
                if (fidId != null && fidId.startsWith("3")) {
                    noPrikeyIconView.setImageResource(R.drawable.ic_people);
                } else {
                    noPrikeyIconView.setImageResource(R.drawable.ic_no_prikey);
                }
                noPrikeyIconView.setVisibility(View.VISIBLE);
            } else {
                noPrikeyIconView.setVisibility(View.GONE);
            }
        }

        // Set up click listeners if enabled
        if (enableClickListeners && clickListener != null) {
            if (avatar != null) {
                avatar.setOnClickListener(v -> clickListener.onAvatarClick(keyInfo.getId()));
            }
            if (nameTextView != null) {
                nameTextView.setOnClickListener(v -> clickListener.onNameClick(keyInfo.getId()));
            }
            if (labelTextView != null) {
                labelTextView.setOnClickListener(v -> clickListener.onLabelClick(keyInfo));
            }
            if (editIconView != null) {
                editIconView.setOnClickListener(v -> clickListener.onEditLabelClick(keyInfo));
            }
            if (noPrikeyIconView != null) {
                noPrikeyIconView.setOnClickListener(v -> clickListener.onNoPrikeyIconClick(keyInfo));
            }
            // Set click listener for the entire card
            cardView.setOnClickListener(v -> clickListener.onCardClick(keyInfo));
        }
    }

    /**
     * Creates a FID card for a multisig ID (when you only have the ID, not full KeyInfo)
     * @param context The context to use for inflating the layout
     * @param multisignId The multisig ID to display
     * @param parent The parent view to add the card to (can be null if you want to handle adding manually)
     * @param enableClickListeners Whether to enable click listeners for copying and avatar display
     * @param clickListener Optional click listener for handling custom clicks (can be null)
     * @return The configured card view
     */
    public static View createMultisignFidCard(Context context, String multisignId, ViewGroup parent, boolean enableClickListeners, MultisigCardClickListener clickListener) {
        if (multisignId == null || multisignId.isEmpty()) {
            return null;
        }

        View cardView = LayoutInflater.from(context).inflate(R.layout.layout_fid_card, parent, false);
        setupMultisignFidCardView(context, cardView, multisignId, enableClickListeners, clickListener);
        
        if (parent != null) {
            parent.addView(cardView);
        }
        
        return cardView;
    }

    /**
     * Sets up an existing FID card view with multisig ID data
     * @param context The context to use
     * @param cardView The card view to configure (should use layout_fid_card)
     * @param multisignId The multisig ID to display
     * @param enableClickListeners Whether to enable click listeners for copying and avatar display
     * @param clickListener Optional click listener for handling custom clicks (can be null)
     */
    public static void setupMultisignFidCardView(Context context, View cardView, String multisignId, boolean enableClickListeners, MultisigCardClickListener clickListener) {
        if (cardView == null || multisignId == null) return;

        ImageView avatar = cardView.findViewById(R.id.fidAvatar);
        TextView nameTextView = cardView.findViewById(R.id.fidName);
        TextView labelTextView = cardView.findViewById(R.id.fidLabel);
        ImageView editIconView = cardView.findViewById(R.id.fidEditIcon);
        TextView cashTextView = cardView.findViewById(R.id.fidCash);
        TextView balanceTextView = cardView.findViewById(R.id.fidBalance);
        TextView cdTextView = cardView.findViewById(R.id.fidCd);
        ImageView noPrikeyIconView = cardView.findViewById(R.id.multisigIcon);

        // Set avatar
        if (avatar != null) {
            try {
                AvatarManager avatarManager = AvatarManager.getInstance(context);
                android.graphics.Bitmap avatarBitmap = avatarManager.getAvatarBitmap(multisignId);
                if (avatarBitmap != null) {
                    avatar.setImageBitmap(avatarBitmap);
                } else {
                    avatar.setImageResource(R.drawable.ic_person);
                }
            } catch (Exception e) {
                avatar.setImageResource(R.drawable.ic_person);
            }
        }

        // Set name (display the multisig ID)
        if (nameTextView != null) {
            nameTextView.setText(multisignId);
        }

        // Hide label and edit icon for multisig senders
        if (labelTextView != null) {
            labelTextView.setVisibility(View.GONE);
        }
        if (editIconView != null) {
            editIconView.setVisibility(View.GONE);
        }

        // Hide cash, balance, cd for multisig (they don't have individual values)
        if (cashTextView != null) {
            cashTextView.setText("-");
        }
        if (balanceTextView != null) {
            balanceTextView.setText("-");
        }
        if (cdTextView != null) {
            cdTextView.setText("-");
        }

        // Show people icon for multisig FID (starts with '3')
        if (noPrikeyIconView != null) {
            if (multisignId.startsWith("3")) {
                noPrikeyIconView.setImageResource(R.drawable.ic_people);
                noPrikeyIconView.setVisibility(View.VISIBLE);
            } else {
                noPrikeyIconView.setVisibility(View.GONE);
            }
        }

        // Set up click listeners if enabled
        if (enableClickListeners && clickListener != null) {
            if (avatar != null) {
                avatar.setOnClickListener(v -> clickListener.onAvatarClick(multisignId));
            }
            if (nameTextView != null) {
                nameTextView.setOnClickListener(v -> clickListener.onNameClick(multisignId));
            }
        }
    }

    /**
     * Formats balance for display
     */
    public static String formatBalance(double balance) {
        if (balance >= 100000) {
            return formatLargeNumber((long) balance);
        } else if (balance >= 1000) {
            return String.valueOf((long) balance);
        } else {
            return String.valueOf(balance);
        }
    }

    /**
     * Formats large numbers with k, m, b suffixes
     */
    public static String formatLargeNumber(long number) {
        if (number >= 1000000000) {
            return String.format("%.1fb", number / 1000000000.0);
        } else if (number >= 1000000) {
            return String.format("%.1fm", number / 1000000.0);
        } else if (number >= 1000) {
            return String.format("%.1fk", number / 1000.0);
        } else {
            return String.valueOf(number);
        }
    }

    /**
     * Interface for handling FID card click events
     */
    public interface FidCardClickListener {
        default void onAvatarClick(String fid) {}
        default void onNameClick(String fid) {}
        default void onLabelClick(KeyInfo keyInfo) {}
        default void onEditLabelClick(KeyInfo keyInfo) {}
        default void onNoPrikeyIconClick(KeyInfo keyInfo) {}
        default void onCardClick(KeyInfo keyInfo) {}
    }

    /**
     * Interface for handling multisig card click events
     */
    public interface MultisigCardClickListener {
        default void onAvatarClick(String multisignId) {}
        default void onNameClick(String multisignId) {}
    }
}