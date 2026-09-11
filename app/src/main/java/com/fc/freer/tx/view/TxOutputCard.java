package com.fc.freer.tx.view;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.freer.nobody.NobodyRegistry;
import com.fc.freer.nobody.NobodyUi;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ToastUtils;

import androidx.cardview.widget.CardView;

import com.fc.fc_ajdk.feature.avatar.AvatarMaker;
import com.fc.fc_ajdk.utils.NumberUtils;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.manager.AvatarManager;

import java.io.IOException;
import java.util.Map;

public class TxOutputCard extends CardView {
    private ImageView avatarImage;
    private TextView fidText;
    private TextView amountText;
    private ImageButton deleteButton;
    private android.widget.LinearLayout lockTimeContainer;
    private ImageView lockTimeIcon;
    private android.widget.TextView lockTimeDaysText;
    private Cash cash;
    private OnDeleteListener onDeleteListener;
    private OnValueChangeListener onValueChangeListener;
    private boolean withDelete;
    private boolean editable;
    private Map<String, Boolean> fidNobodyMap;

    public interface OnDeleteListener {
        void onDelete(TxOutputCard card);
    }

    public interface OnValueChangeListener {
        void onFidChanged(String newFid);
        void onAmountChanged(double newAmount);
    }

    public TxOutputCard(Context context) {
        super(context);
        init(context);
    }

    public TxOutputCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public TxOutputCard(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.layout_tx_output_card, this, true);
        avatarImage = findViewById(R.id.avatarImage);
        fidText = findViewById(R.id.fidText);
        amountText = findViewById(R.id.amountText);
        deleteButton = findViewById(R.id.deleteButton);
        lockTimeContainer = findViewById(R.id.lockTimeContainer);
        lockTimeIcon = findViewById(R.id.lockTimeIcon);
        lockTimeDaysText = findViewById(R.id.lockTimeDaysText);

        // Setup copy functionality for FID
        fidText.setOnClickListener(v -> {
            if (cash != null) {
                copyToClipboard(context, getContext().getString(R.string.fid), cash.getOwner());
            }
        });

        // Setup copy functionality for Amount
        amountText.setOnClickListener(v -> {
            if (cash != null) {
                String amount = NumberUtils.formatAmount(cash.getAmount()) + " F";
                copyToClipboard(context, getContext().getString(R.string.amount), amount);
            }
        });

        // Add text change listeners
        fidText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (onValueChangeListener != null && cash != null) {
                    onValueChangeListener.onFidChanged(s.toString());
                }
            }
        });

        amountText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                if (onValueChangeListener != null && cash != null) {
                    try {
                        double amount = Double.parseDouble(s.toString());
                        onValueChangeListener.onAmountChanged(amount);
                    } catch (NumberFormatException e) {
                        // Invalid number format, ignore
                    }
                }
            }
        });

        // Setup focus change listeners to hide keyboard when focus is lost
        fidText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                hideKeyboard(context, v);
            }
        });

        amountText.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                hideKeyboard(context, v);
            }
        });

        deleteButton.setOnClickListener(v -> {
            if (onDeleteListener != null) {
                onDeleteListener.onDelete(this);
            }
        });

        // Setup click listener for avatar to show big image
        avatarImage.setOnClickListener(v -> {
            if (cash != null) {
                AvatarManager.showAvatarDialog(context, cash.getOwner());
            }
        });

        // Add long press listener for FID list operations
        View.OnLongClickListener longPressListener = v -> {
            if (cash != null) {
                android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(context);
                String[] options = {context.getString(R.string.add_to_fid_list), context.getString(R.string.clear_fid_list)};
                builder.setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: // Add to FID list
                            FreerApplication.addFid(cash.getOwner());
                            ToastUtils.makeText(context, context.getString(R.string.add_to_fid_list));
                            break;
                        case 1: // Clear FID list
                            FreerApplication.clearFidList();
                            ToastUtils.makeText(context, context.getString(R.string.fid_list_cleared));
                            break;
                    }
                });
                DialogUtils.show(builder);
                return true;
            }
            return false;
        };

        // Apply long press listener to both the card and FID text
        this.setOnLongClickListener(longPressListener);
        fidText.setOnLongClickListener(longPressListener);

        // Setup touch listener for the card to handle clicks outside edit fields
        this.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // Check if the touch is outside the edit fields
                if (!isTouchInsideEditFields(event)) {
                    // Clear focus from both edit fields
                    fidText.clearFocus();
                    amountText.clearFocus();
                    // Hide keyboard
                    hideKeyboard(context, v);
                }
            }
            return false;
        });
    }

    private boolean isTouchInsideEditFields(MotionEvent event) {
        // Get the touch coordinates
        float x = event.getX();
        float y = event.getY();

        // Check if the touch is inside the FID edit field
        if (isPointInsideView(x, y, fidText)) {
            return true;
        }

        // Check if the touch is inside the Amount edit field
        if (isPointInsideView(x, y, amountText)) {
            return true;
        }

        return false;
    }

    private boolean isPointInsideView(float x, float y, View view) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        int left = location[0];
        int top = location[1];
        int right = left + view.getWidth();
        int bottom = top + view.getHeight();

        return x >= left && x <= right && y >= top && y <= bottom;
    }

    private void hideKeyboard(Context context, View view) {
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private void copyToClipboard(Context context, String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        ToastUtils.makeText(context, R.string.copied);
    }

    public void setSendTo(Cash cash, Context context, boolean withDelete, boolean nobody) {
        setSendTo(cash, context, withDelete, true, nobody);
    }

    public void setSendTo(Cash cash, Context context, boolean withDelete, boolean editable, boolean nobody) {
        this.cash = cash;
        this.withDelete = withDelete;
        this.editable = editable;

        fidText.setText(cash.getOwner());
        fidText.setMaxLines(2);
        amountText.setText(NumberUtils.formatAmount(cash.getAmount()) + " F");
        deleteButton.setVisibility(withDelete ? View.VISIBLE : View.GONE);

        fidText.setEnabled(editable);
        amountText.setEnabled(editable);

        // A caller's on-chain check (e.g. SendTx's checkNobodies) also counts.
        // The FID field stays plain text: it is editable and read back as a FID,
        // so the mark lives on the avatar only.
        if (nobody) NobodyRegistry.get().mark(cash.getOwner());

        // Load avatar (marked when the FID is a nobody: leaked/public prikey)
        try {
            byte[] avatarBytes = AvatarMaker.makeAvatar(cash.getOwner(),context);
            Bitmap avatar = BitmapFactory.decodeByteArray(avatarBytes, 0, avatarBytes.length);
            avatarImage.setImageBitmap(NobodyUi.avatar(context, cash.getOwner(), avatar));
        } catch (IOException e) {
            ToastUtils.makeText(context, R.string.failed_to_load_avatar);
        }
    }

    public void setSendTo(Cash cash, Context context) {
        setSendTo(cash, context, false, false);
    }

    public Cash getSendTo() {
        return cash;
    }

    public void setOnDeleteListener(OnDeleteListener listener) {
        this.onDeleteListener = listener;
    }

    public void setOnValueChangeListener(OnValueChangeListener listener) {
        this.onValueChangeListener = listener;
    }

    /**
     * Sets locktime information for CLTV outputs
     * @param lockTime The block height at which the output becomes spendable
     * @param bestHeight The current best block height
     */
    public void setLockTime(Long lockTime, long bestHeight) {
        if (lockTime == null || lockTime <= 0) {
            lockTimeContainer.setVisibility(View.GONE);
            return;
        }

        lockTimeContainer.setVisibility(View.VISIBLE);

        // Calculate blocks and days until unlock
        long blocksToLock = lockTime - bestHeight;
        boolean isUnlocked = blocksToLock <= 0;
        if(blocksToLock < 0) blocksToLock = 0;
        long daysToLock = (long) ((blocksToLock+1) / (24.0 * 60)); // 1 block ≈ 1 minute

        // Set the days text (primary info)
        String daysInfo = String.format(java.util.Locale.US, "%d %s (%d %s to %d)",
            daysToLock,
            getContext().getString(R.string.days),
            blocksToLock,
            getContext().getString(R.string.blocks),lockTime);
        lockTimeDaysText.setText(daysInfo);
        lockTimeIcon.setVisibility(VISIBLE);

        // Set green color and unlock icon for unlocked outputs
        if (isUnlocked) {
            lockTimeIcon.setImageResource(R.drawable.ic_unlock);
            lockTimeIcon.setColorFilter(getContext().getColor(android.R.color.holo_green_dark));
            lockTimeDaysText.setTextColor(getContext().getColor(android.R.color.holo_green_dark));
        } else {
            lockTimeIcon.setImageResource(R.drawable.ic_lock);
            lockTimeIcon.clearColorFilter();
            lockTimeDaysText.setTextColor(getContext().getColor(R.color.warning));
        }
        // Make the locktime container clickable to copy locktime value
        lockTimeContainer.setClickable(true);
        lockTimeContainer.setFocusable(true);
        lockTimeContainer.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("lockTime", String.valueOf(lockTime));
            clipboard.setPrimaryClip(clip);
        });
    }
} 