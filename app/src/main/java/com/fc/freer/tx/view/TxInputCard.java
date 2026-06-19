package com.fc.freer.tx.view;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.cardview.widget.CardView;

import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.NumberUtils;
import com.fc.freer.R;
import com.fc.freer.utils.ToastUtils;

public class TxInputCard extends CardView {
    private TextView txIdText;
    private TextView indexText;
    private TextView amountText;
    private ImageButton deleteButton;
    private android.widget.LinearLayout lockTimeContainer;
    private android.widget.ImageView lockTimeIcon;
    private android.widget.TextView lockTimeDaysText;
    private Cash cash;
    private OnDeleteListener onDeleteListener;

    public interface OnDeleteListener {
        void onDelete(TxInputCard card);
    }

    public TxInputCard(Context context) {
        super(context);
        init(context);
    }

    public TxInputCard(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public TxInputCard(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        LayoutInflater.from(context).inflate(R.layout.layout_tx_input_card, this, true);
        
        // Set CardView background to transparent to remove the default white background
        setCardBackgroundColor(android.graphics.Color.TRANSPARENT);
        
        // Remove shadow by setting elevation to 0
        setCardElevation(0f);
        
        txIdText = findViewById(R.id.txIdText);
        indexText = findViewById(R.id.indexText);
        amountText = findViewById(R.id.amountText);
        deleteButton = findViewById(R.id.deleteButton);
        lockTimeContainer = findViewById(R.id.lockTimeContainer);
        lockTimeIcon = findViewById(R.id.lockTimeIcon);
        lockTimeDaysText = findViewById(R.id.lockTimeDaysText);

        // Setup copy functionality for TxId
        txIdText.setOnClickListener(v -> {
            if (cash != null) {
                copyToClipboard(context, getContext().getString(R.string.txid), cash.getBirthTxId());
            }
        });

        // Setup copy functionality for Amount
        amountText.setOnClickListener(v -> {
            if (cash != null) {
                String amount = NumberUtils.formatAmount(FchUtils.satoshiToCash(cash.getValue()));
                copyToClipboard(context, getContext().getString(R.string.amount), amount);
            }
        });

        deleteButton.setOnClickListener(v -> {
            if (onDeleteListener != null) {
                onDeleteListener.onDelete(this);
            }
        });
    }

    private void copyToClipboard(Context context, String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        ToastUtils.makeText(context, R.string.copied);
    }

    public void setCash(Cash cash) {
        this.cash = cash;
        txIdText.setText(cash.getBirthTxId());
        indexText.setText(String.valueOf(cash.getBirthIndex()));
        amountText.setText(NumberUtils.formatAmount(FchUtils.satoshiToCoin(cash.getValue())) + " F");
    }

    public Cash getCash() {
        return cash;
    }

    public void setOnDeleteListener(OnDeleteListener listener) {
        this.onDeleteListener = listener;
    }

    /**
     * Sets locktime information for CLTV inputs
     * @param lockTime The block height at which the input becomes spendable
     * @param bestHeight The current best block height
     */
    public void setLockTime(Long lockTime, long bestHeight) {
        if (lockTime == null || lockTime <= 0) {
            lockTimeContainer.setVisibility(android.view.View.GONE);
            return;
        }

        lockTimeContainer.setVisibility(android.view.View.VISIBLE);

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
        lockTimeIcon.setVisibility(android.view.View.VISIBLE);

        // Set green color and unlock icon for unlocked inputs
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