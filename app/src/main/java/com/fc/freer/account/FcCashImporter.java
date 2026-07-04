package com.fc.freer.account;

import android.content.Context;
import android.content.Intent;
import android.view.inputmethod.InputMethodManager;
import android.view.View;

import com.fc.freer.utils.ToastUtils;

import androidx.annotation.Nullable;

import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.utils.JsonUtils;
import com.fc.freer.R;
import com.fc.freer.ui.SingleInputActivity;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class FcCashImporter {
    private final Context context;
    private final OnImportListener listener;
    private String type;
    private List<Cash> finalCashList;

    public interface OnImportListener {
        void onImportSuccess(List<Cash> result);
        void onImportError(String error);
    }

    public FcCashImporter(Context context, OnImportListener listener) {
        this.context = context;
        this.listener = listener;
        this.finalCashList = new ArrayList<>();
    }

    public void setType(String type) {
        this.type = type;
    }

    public List<Cash> importEntity(String jsonText) {
        if (jsonText.isEmpty()) {
            listener.onImportError("Please input JSON text");
            return null;
        }
        byte[] jsonBytes = jsonText.getBytes(StandardCharsets.UTF_8);

        if(jsonBytes==null || jsonBytes.length==0)return null;

        try(InputStream is = new ByteArrayInputStream(jsonBytes)){
            return importEntity(is);
        } catch (Exception e) {
            ToastUtils.makeText(context, R.string.failed_to_parse_json);
            return null;
        }
    }

    @Nullable
    public List<Cash> importEntity(InputStream is) throws Exception {
        List<Cash> cashList = JsonUtils.readMultipleJsonObjectsFromInputStream(is, Cash.class);
        if(cashList.isEmpty()){
            listener.onImportError(context.getString(R.string.no_valid_cash_found));
            return null;
        }

        // Get the current live FID for validation
        String liveFid = null;
        try {
            com.fc.freer.manager.FidManager fidManager = com.fc.freer.manager.FidManager.getInstance();
            if (fidManager != null) {
                liveFid = fidManager.getLiveFid();
            }
        } catch (Exception e) {
            // Log error but continue with validation disabled
        }

        List<Cash> validCashList = new ArrayList<>();
        int rejectedCount = 0;

        // Ensure each cash has an ID and validate owner and validity
        for (Cash cash : cashList) {
            if (cash.getId() == null) {
                cash.makeId();
            }
            
            // CD is computed later against the current best block height (no bestHeight here).
            // Imported cash carries birthHeight in its JSON, so CD self-heals on the next recompute.
            
            // Validate cash: treat as valid if the field is null (not present in imported JSON) or explicitly true
            if(cash.isValid()==null)cash.setValid(Boolean.TRUE);
            boolean isValidCash = cash.isValid();
            boolean isOwnerValid = true;
            
            if (liveFid != null) {
                String cashOwner = cash.getOwner();
                isOwnerValid = (cashOwner == null || cashOwner.equals(liveFid));
            }
            
            if (isValidCash && isOwnerValid) {
                validCashList.add(cash);
            } else {
                rejectedCount++;
            }
        }

        if (validCashList.isEmpty()) {
            listener.onImportError(context.getString(R.string.no_valid_cash_found));
            return null;
        }

        finalCashList.addAll(validCashList);
        
        // Show info about rejected items if any
        if (rejectedCount > 0) {
            ToastUtils.makeText(context, context.getString(R.string.toast_imported_cash, validCashList.size(), rejectedCount));
        }
        
        listener.onImportSuccess(finalCashList);
        return finalCashList;
    }

    public void handleInputResult(Intent data) {
        if (data == null) {
            listener.onImportError("Input cancelled");
            return;
        }

        String input = data.getStringExtra(SingleInputActivity.EXTRA_RESULT);
        if (input != null) {
            // Handle any additional input if needed
            listener.onImportError("Unexpected input result");
        }
    }

    public static void hideKeyboard(View view) {
        if (view != null) {
            InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
            }
        }
    }
} 