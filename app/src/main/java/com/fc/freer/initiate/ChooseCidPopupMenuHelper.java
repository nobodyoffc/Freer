package com.fc.freer.initiate;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;
import android.widget.TextView;

import com.fc.freer.R;
import com.fc.freer.myKeys.CreateKeyByFidActivity;
import com.fc.freer.myKeys.CreateKeyByPhraseActivity;
import com.fc.freer.myKeys.CreateKeyByPrikeyActivity;
import com.fc.freer.myKeys.CreateKeyByPrikeyCipherActivity;
import com.fc.freer.myKeys.CreateKeyByPubkeyActivity;
import com.fc.freer.myKeys.FindNiceKeysActivity;
import com.fc.freer.myKeys.ImportKeyInfoActivity;
import com.fc.freer.myKeys.RandomNewKeysActivity;

public class ChooseCidPopupMenuHelper {
    private static final String TAG = "ChooseCidPopupMenu";

    private final Context context;
    private PopupWindow popupWindow;

    public ChooseCidPopupMenuHelper(Context context) {
        this.context = context;
    }

    public void showCreateKeyMenu(View anchorView) {
        View popupView = LayoutInflater.from(context).inflate(R.layout.popup_menu_create_cid, null);
        popupWindow = new PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popupWindow.setElevation(10f);

        setupCreateKeyMenuItems(popupView);
        showPopup(anchorView);
    }

    private void setupCreateKeyMenuItems(View popupView) {
        TextView fromPriKey = popupView.findViewById(R.id.from_pri_key);
        TextView fromPriKeyCipher = popupView.findViewById(R.id.from_pri_key_cipher);
        TextView fromPhrase = popupView.findViewById(R.id.input_phrase);
        TextView createNewKeys = popupView.findViewById(R.id.random_new_keys);
        TextView importKeys = popupView.findViewById(R.id.import_keys);
        TextView inputPubkey = popupView.findViewById(R.id.input_pubkey);
        TextView inputFid = popupView.findViewById(R.id.input_fid);
        TextView findNiceFid = popupView.findViewById(R.id.find_nice_fid);


        createNewKeys.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(context, RandomNewKeysActivity.class);
            if (context instanceof ChooseCidActivity) {
                ((ChooseCidActivity) context).startActivityForResult(intent, ChooseCidActivity.REQUEST_CODE_CREATE_KEY);
            } else if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).startActivityForResult(intent, 1001);
            } else {
                // For non-Activity contexts, we need to handle this differently
                // This will be handled by the calling code
                context.startActivity(intent);
            }
        });

        fromPhrase.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(context, CreateKeyByPhraseActivity.class);
            if (context instanceof ChooseCidActivity) {
                ((ChooseCidActivity) context).startActivityForResult(intent, ChooseCidActivity.REQUEST_CODE_CREATE_KEY);
            } else if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).startActivityForResult(intent, 1001);
            } else {
                // For non-Activity contexts, we need to handle this differently
                // This will be handled by the calling code
                context.startActivity(intent);
            }
        });

        fromPriKey.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(context, CreateKeyByPrikeyActivity.class);
            if (context instanceof ChooseCidActivity) {
                ((ChooseCidActivity) context).startActivityForResult(intent, ChooseCidActivity.REQUEST_CODE_CREATE_KEY);
            } else if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).startActivityForResult(intent, 1001);
            } else {
                // For non-Activity contexts, we need to handle this differently
                // This will be handled by the calling code
                context.startActivity(intent);
            }
        });

        fromPriKeyCipher.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(context, CreateKeyByPrikeyCipherActivity.class);
            if (context instanceof ChooseCidActivity) {
                ((ChooseCidActivity) context).startActivityForResult(intent, ChooseCidActivity.REQUEST_CODE_CREATE_KEY);
            } else if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).startActivityForResult(intent, 1001);
            } else {
                // For non-Activity contexts, we need to handle this differently
                // This will be handled by the calling code
                context.startActivity(intent);
            }
        });

        importKeys.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(context, ImportKeyInfoActivity.class);
            if (context instanceof ChooseCidActivity) {
                ((ChooseCidActivity) context).startActivityForResult(intent, ChooseCidActivity.REQUEST_CODE_CREATE_KEY);
            } else if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).startActivityForResult(intent, 1001);
            } else {
                // For non-Activity contexts, we need to handle this differently
                // This will be handled by the calling code
                context.startActivity(intent);
            }
        });

        inputPubkey.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(context, CreateKeyByPubkeyActivity.class);
            if (context instanceof ChooseCidActivity) {
                ((ChooseCidActivity) context).startActivityForResult(intent, ChooseCidActivity.REQUEST_CODE_CREATE_KEY);
            } else if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).startActivityForResult(intent, 1001);
            } else {
                // For non-Activity contexts, we need to handle this differently
                // This will be handled by the calling code
                context.startActivity(intent);
            }
        });

        inputFid.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(context, CreateKeyByFidActivity.class);
            if (context instanceof ChooseCidActivity) {
                ((ChooseCidActivity) context).startActivityForResult(intent, ChooseCidActivity.REQUEST_CODE_CREATE_KEY);
            } else if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).startActivityForResult(intent, 1001);
            } else {
                // For non-Activity contexts, we need to handle this differently
                // This will be handled by the calling code
                context.startActivity(intent);
            }
        });

        findNiceFid.setOnClickListener(v -> {
            popupWindow.dismiss();
            Intent intent = new Intent(context, FindNiceKeysActivity.class);
            if (context instanceof ChooseCidActivity) {
                ((ChooseCidActivity) context).startActivityForResult(intent, ChooseCidActivity.REQUEST_CODE_CREATE_KEY);
            } else if (context instanceof android.app.Activity) {
                ((android.app.Activity) context).startActivityForResult(intent, 1001);
            } else {
                // For non-Activity contexts, we need to handle this differently
                // This will be handled by the calling code
                context.startActivity(intent);
            }
        });
    }

    private void showPopup(View anchorView) {
        int[] location = new int[2];
        anchorView.getLocationOnScreen(location);
        popupWindow.showAtLocation(anchorView, android.view.Gravity.NO_GRAVITY, 
            location[0], location[1] - popupWindow.getHeight());
    }
} 