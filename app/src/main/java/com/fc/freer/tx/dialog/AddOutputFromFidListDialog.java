package com.fc.freer.tx.dialog;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.widget.Button;

import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.freer.utils.ToastUtils;

import androidx.annotation.NonNull;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.utils.FchUtils;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.google.android.material.textfield.TextInputEditText;

import java.util.List;

public class AddOutputFromFidListDialog extends Dialog {
    private static final String TAG = "AddOutputFromFidListDialog";
    private final boolean checkRest;
    private TextInputEditText amountInput;
    private Button cancelButton;
    private Button addButton;
    private final RawTxInfo rawTxInfo;
    private final long rest;
    private OnDoneListener onDoneListener;
    private List<String> fidList;
    private Long lockTime;

    public interface OnDoneListener {
        void onDone(List<Cash> cashList);
    }

    public AddOutputFromFidListDialog(@NonNull Context context, List<String>fidList, RawTxInfo rawTxInfo, long rest, boolean checkRest) {
        this(context, fidList, rawTxInfo, rest, checkRest, null);
    }

    public AddOutputFromFidListDialog(@NonNull Context context, List<String>fidList, RawTxInfo rawTxInfo, long rest, boolean checkRest, Long lockTime) {
        super(context);
        this.rawTxInfo = rawTxInfo;
        this.rest = rest-34;
        this.fidList = fidList;
        this.checkRest = checkRest;
        this.lockTime = lockTime;
        if (context instanceof android.app.Activity) {
            setOwnerActivity((android.app.Activity) context);
        } else {
            TimberLogger.e(TAG, "Context is not an Activity");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_add_output_from_fid_list);

        amountInput = findViewById(R.id.amountInput);
        cancelButton = findViewById(R.id.cancelButton);
        addButton = findViewById(R.id.addButton);

        cancelButton.setOnClickListener(v -> dismiss());

        addButton.setOnClickListener(v -> {
            if (amountInput.getText() == null || amountInput.getText().toString().isEmpty()) {
                ToastUtils.makeText(getContext(), R.string.please_input_amount);
                return;
            }

            double amount = Double.parseDouble(amountInput.getText().toString());
            
            // Validate amount range
            if (amount < Constants.MIN_AMOUNT || amount > Constants.MAX_AMOUNT) {
                ToastUtils.makeText(getContext(), getContext().getString(R.string.amount_must_be_between, Constants.MIN_AMOUNT, Constants.MAX_AMOUNT));
                return;
            }

            // Get FID list
            if (fidList.isEmpty()) {
                ToastUtils.makeText(getContext(), R.string.fid_list_is_empty);
                return;
            }



            // Validate total amount against rest
            if(checkRest){
                double totalAmount = amount * fidList.size();
                if (totalAmount > FchUtils.satoshiToCoin(rest)) {
                    ToastUtils.makeText(getContext(),  R.string.total_amount_exceeds_available_balance);
                    return;
                }
            }

            // Create Cash list
            List<Cash> cashList = new java.util.ArrayList<>();
            for (String fid : fidList) {
                Cash cash = new Cash(fid, amount, lockTime);
                cashList.add(cash);
                rawTxInfo.getOutputs().add(cash);
            }

            if (onDoneListener != null) {
                onDoneListener.onDone(cashList);
            }

            dismiss();
        });
    }

    public void setOnDoneListener(OnDoneListener listener) {
        this.onDoneListener = listener;
    }
} 