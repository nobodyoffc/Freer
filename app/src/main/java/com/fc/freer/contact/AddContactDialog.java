package com.fc.freer.contact;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.crypto.Encryptor;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fcData.AlgorithmId;
import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.fc_ajdk.data.feipData.ContactOpData;
import com.fc.fc_ajdk.data.feipData.Feip;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.freer.R;
import com.fc.freer.manager.CashManager;
import com.fc.freer.manager.ContactManager;
import com.fc.freer.manager.FidManager;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.core.fch.TxHandler;
import com.fc.freer.tx.TxSender;
import com.fc.freer.utils.DialogUtils;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.SecurePrikeyManager;
import com.fc.freer.utils.ToastUtils;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;

import static com.fc.fc_ajdk.constants.IndicesNames.CONTACT;

public class AddContactDialog {
    private static final String TAG = "AddContactDialog";


    private Context context;
    private AlertDialog dialog;
    private Contact currentContact;

    // UI Components
    private TextView fidDisplayText;
    private TextInputEditText titlesInput;
    private TextInputEditText memoInput;
    private CheckBox seeStatementCheckBox;
    private CheckBox seeWritingsCheckBox;
    private Button stopButton;
    private Button nextButton;
    private Button carveButton;

    // Callback interface
    public interface AddContactDialogCallback {
        void onStop();
        void onNext();
        void onCarveSuccess(Contact contact);
        void onCarveError(String errorMessage);
    }

    private AddContactDialogCallback callback;

    public AddContactDialog(Context context, Contact contact, AddContactDialogCallback callback) {
        this.context = context;
        this.currentContact = contact;
        this.callback = callback;
        createDialog();
    }

    private void createDialog() {
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_add_contact, null);

        // Initialize views
        initializeViews(dialogView);

        // Setup button listeners
        setupButtons();

        // Set FID display
        String displayFid;
        if(currentContact.getCid() != null){
            displayFid= currentContact.getCid();
        }else {
            displayFid= currentContact.getFid();
        }
        if(fidDisplayText!= null)
            fidDisplayText.setText(displayFid);

        // Create dialog
        dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        ContactManager contactManager = ContactManager.getInstance();
        if (contactManager !=null && contactManager.checkIfFidExisted(currentContact.getFid())){
            if(carveButton!=null){
                carveButton.setEnabled(false);
                carveButton.setAlpha(0.5f);
            }
            if (callback != null) {
                ToastUtils.makeText(context, context.getString(R.string.contact_existed_title));
            }
        }
    }

    private void initializeViews(View dialogView) {
        fidDisplayText = dialogView.findViewById(R.id.fidDisplayText);

        // Get input views directly
        titlesInput = dialogView.findViewById(R.id.titlesInput);
        titlesInput.setHint(R.string.input_titles_comma_separated);
        memoInput = dialogView.findViewById(R.id.memoInput);
        memoInput.setHint(context.getString(R.string.input_the_memo) + " (optional)");

        seeStatementCheckBox = dialogView.findViewById(R.id.seeStatementCheckBox);
        seeWritingsCheckBox = dialogView.findViewById(R.id.seeWritingsCheckBox);

        stopButton = dialogView.findViewById(R.id.stopButton);
        nextButton = dialogView.findViewById(R.id.nextButton);
        carveButton = dialogView.findViewById(R.id.carveButton);

        // Set default checkbox values
        seeStatementCheckBox.setChecked(true);
        seeWritingsCheckBox.setChecked(true);
    }

    private void setupButtons() {
        stopButton.setOnClickListener(v -> {
            if (callback != null) {
                callback.onStop();
            }
            dismiss();
        });

        nextButton.setOnClickListener(v -> {
            if (callback != null) {
                callback.onNext();
            }
            dismiss();
        });

        carveButton.setOnClickListener(v -> carveContact());
    }

    private void carveContact() {

        String titlesText = titlesInput.getText() != null ? titlesInput.getText().toString().trim() : "";
        String memo = memoInput.getText() != null ? memoInput.getText().toString().trim() : "";
        boolean seeStatement = seeStatementCheckBox.isChecked();
        boolean seeWritings = seeWritingsCheckBox.isChecked();

        // Parse titles by comma
        List<String> titlesList = new ArrayList<>();
        if (!titlesText.isEmpty()) {
            String[] titlesArray = titlesText.split(",");
            for (String title : titlesArray) {
                String trimmedTitle = title.trim();
                if (!trimmedTitle.isEmpty()) {
                    titlesList.add(trimmedTitle);
                }
            }
        }

        KeyInfo liveKeyInfo = FidManager.getInstance().getLiveKeyInfo();
        if (liveKeyInfo == null) {
            if (callback != null) {
                callback.onCarveError("No live key available");
            }
            return;
        }

        String pubkey = liveKeyInfo.getPubkey();
        if (pubkey == null) {
            if (callback != null) {
                callback.onCarveError("Invalid public key");
            }
            return;
        }

        Contact contact = new Contact();
        contact.setFid(currentContact.getFid());
        if (!titlesList.isEmpty()) {
            contact.setTitles(titlesList);
            currentContact.setTitles(titlesList);
        }
        if (!memo.isEmpty()) {
            contact.setMemo(memo);
            currentContact.setMemo(memo);
        }
        contact.setSeeStatement(seeStatement);
        currentContact.setSeeStatement(seeStatement);
        contact.setSeeWritings(seeWritings);
        currentContact.setSeeWritings(seeWritings);

        String feipJson = makeAddContactFeip(contact, pubkey);

        byte[] prikey = SecurePrikeyManager.fetchPrikeySilent(liveKeyInfo.getPrikeyCipher());
        if (prikey != null) {
            new Thread(() -> {
                CashManager cashManager = CashManager.getInstance();
                TxSender txSender = new TxSender();
                txSender.carveSimpleFeip(context, liveKeyInfo.getId(), feipJson, prikey, cashManager, new TxHandler(),
                    (FapiClient) ApiCenter.getInstance().getClient(Service.ServiceType.FAPI_No1_NrC7), new TxSender.TxCallback() {
                    @Override
                    public void onSuccess(String txId) {
                        if (context instanceof ContactActivity) {
                            ((ContactActivity) context).runOnUiThread(() -> {
                                currentContact.setId(txId);
                                currentContact.setOnChain(null);
                                currentContact.setLastHeight(Constants.MaX_HEIGHT);
                                ContactManager contactManager = ContactManager.getInstance();
                                contactManager.addContact(currentContact);
                                contactManager.commit();

                                if (callback != null) {
                                    callback.onCarveSuccess(currentContact);
                                }
                                dismiss();
                            });
                        }
                    }

                    @Override
                    public void onError(String errorMessage) {
                        if (context instanceof ContactActivity) {
                            ((ContactActivity) context).runOnUiThread(() -> {
                                if (callback != null) {
                                    callback.onCarveError("Failed to carve FEIP on-chain: " + errorMessage);
                                }
                            });
                        }
                    }

                    @Override
                    public void onUnsignedTx(RawTxInfo rawTxInfo) {
                        if (context instanceof ContactActivity) {
                            ((ContactActivity) context).runOnUiThread(() -> {
                                ToastUtils.makeText(context, context.getString(R.string.no_prikey_copy_tx_sign_it_and_broadcast_it));
                                txSender.showUnsignedTxAsQR((ContactActivity) context, rawTxInfo);
                                if (callback != null) {
                                    callback.onCarveError("Cannot sign transaction - showing unsigned TX");
                                }
                            });
                        }
                    }

                    @Override
                    public void onUnbroadcasted(String signedTxHex) {
                        if (context instanceof ContactActivity) {
                            ((ContactActivity) context).runOnUiThread(() -> {
                                txSender.showSignedTxAsQR((ContactActivity) context, signedTxHex);
                                if (callback != null) {
                                    callback.onCarveError("Transaction signed but not broadcasted - showing signed TX");
                                }
                            });
                        }
                    }
                });
            }).start();
        } else {
            if (callback != null) {
                callback.onCarveError("Failed to get private key for transaction signing");
            }
        }
    }

    private static String makeAddContactFeip(Contact contact, String pubkey) {
        String contactDetailCipher = new Encryptor(AlgorithmId.FC_EccK1AesGcm256_No1_NrC7)
            .encryptStrByAsyOneWay(contact.toJson(), pubkey).toJson();
        Feip feip = Feip.fromName(CONTACT);
        ContactOpData contactOpData = ContactOpData.makeAdd(null, contactDetailCipher);
        feip.setData(contactOpData);
        return feip.toJson();
    }


    public void show() {
        if (dialog != null && !dialog.isShowing()) {
            DialogUtils.show(dialog);
        }
    }

    public void dismiss() {
        if (dialog != null && dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }

}