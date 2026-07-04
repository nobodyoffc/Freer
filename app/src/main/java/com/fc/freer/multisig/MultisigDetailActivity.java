package com.fc.freer.multisig;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.fc.fc_ajdk.data.fchData.Multisig;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.utils.ChooseMode;
import com.fc.freer.utils.KeyCardContainer;
import com.fc.freer.utils.ToastUtils;

import com.fc.fc_ajdk.data.fcData.KeyInfo;
import com.fc.fc_ajdk.data.feipData.Contact;
import com.fc.freer.R;
import com.fc.freer.manager.ContactManager;
import com.fc.freer.manager.FidManager;
import com.fc.freer.ui.DetailFragment;

import java.util.ArrayList;
import java.util.List;

public class MultisigDetailActivity extends BaseCryptoActivity {
    private static final String TAG = "MultisigDetailActivity";
    private Multisig multisig;
    private LinearLayout memberCardsContainer;
    private LinearLayout detailContainer;
    private ImageButton saveButton;
    private ImageButton copyButton;
    private KeyCardContainer keyCardContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Get Multisig from intent
        String multisignStr= (String) getIntent().getSerializableExtra("multisig");

        multisig = Multisig.fromJson(multisignStr, Multisig.class);
        if (multisig == null) {
            finish();
            return;
        }

        // Setup member cards
        setupMemberCards();

        // Setup detail fragment
        setupDetailFragment();

        // Setup buttons
        setupButtons();
    }

    @Override
    protected int getLayoutId() {
        return R.layout.activity_multisign_detail;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.multisign_detail);
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
        // Handle QR scan result if needed
    }

    protected void initializeViews() {
        memberCardsContainer = findViewById(R.id.memberCardsContainer);
        detailContainer = findViewById(R.id.detailContainer);
        saveButton = findViewById(R.id.saveButton);
        copyButton = findViewById(R.id.copyButton);

        // Setup copy button
        copyButton.setOnClickListener(v -> {
            String jsonString = multisig.toNiceJson();
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Multisig JSON", jsonString);
            clipboard.setPrimaryClip(clip);
            ToastUtils.makeText(this, getString(R.string.copied));
        });
    }

    private void setupMemberCards() {
        // Add label for members section
        TextView membersLabel = new TextView(this);
        membersLabel.setText("Members");
        membersLabel.setTextSize(18);
        membersLabel.setTypeface(membersLabel.getTypeface(), android.graphics.Typeface.BOLD);
        memberCardsContainer.addView(membersLabel);

        // Create a container for the key cards
        LinearLayout keyCardList = new LinearLayout(this);
        keyCardList.setOrientation(LinearLayout.VERTICAL);
        memberCardsContainer.addView(keyCardList);

        // Initialize KeyCardContainer
        keyCardContainer = new KeyCardContainer(this, keyCardList, ChooseMode.WITHOUT_CHOOSE);

        // Create KeyInfo objects from pubKeys and add them to the card manager
        List<String> pubKeys = multisig.getPubkeys();
        if(pubKeys!=null && !pubKeys.isEmpty())
            for (String pubKey : pubKeys) {
                KeyInfo keyInfo = new KeyInfo(null, pubKey);
                keyCardContainer.addKeyCard(keyInfo);
            }
    }

    private void setupDetailFragment() {
        // Create and add detail fragment
        DetailFragment detailFragment = DetailFragment.newInstance(multisig, Multisig.class);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.detailContainer, detailFragment)
                .commit();
    }

    @Override
    protected void setupButtons() {
        // Setup save button
        saveButton.setOnClickListener(v -> {
            // Convert Multisig to Contact
            KeyInfo keyInfo = new KeyInfo();
            keyInfo.setId(multisig.getId());
            keyInfo.setLabel(multisig.getLabel());
            keyInfo.setMultisign(multisig);
            SettingManager.getInstance().getCurrentSetting().addMultisigKeyInfo(keyInfo);

            Contact contact = convertMultisignToContact(multisig);

            if (contact != null) {
                // Get ContactManager instance
                String liveFid = FidManager.getInstance().getLiveKeyInfo().getId();
                ContactManager contactManager = ContactManager.getInstance(this, liveFid);

                // Save contact
                contactManager.addContact(contact);
                contactManager.commit();

                ToastUtils.makeText(this, getString(R.string.entity_saved_successfully, "Contact"));
            } else {
                ToastUtils.makeText(this, getString(R.string.toast_failed_convert_multisig_contact));
            }
        });

    }

    /**
     * Converts a Multisig object to a Contact object
     * @param multisig The Multisig object to convert
     * @return Contact object with multisig ID as FID and label as title[0]
     */
    private Contact convertMultisignToContact(Multisig multisig) {
        if (multisig == null) {
            return null;
        }

        Contact contact = new Contact();

        // Set FID to the multisig ID
        contact.setFid(multisig.getId());

        // Set title from label if available
        if (multisig.getLabel() != null && !multisig.getLabel().isEmpty()) {
            List<String> titles = new ArrayList<>();
            titles.add(multisig.getLabel());
            contact.setTitles(titles);
        }

        // Set multisig info
        contact.setMultisign(multisig);

        // Set as off-chain contact
        contact.setOnChain(false);

        // Generate ID for the contact
        contact.checkIdWithCreate();

        return contact;
    }
} 