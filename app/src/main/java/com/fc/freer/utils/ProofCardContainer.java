package com.fc.freer.utils;

import static android.view.View.VISIBLE;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.PopupMenu;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.content.res.ColorStateList;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.fc.fc_ajdk.data.feipData.Proof;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CidFidManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class ProofCardContainer {
    private static final String TAG = "ProofCardContainer";
    private final Context context;
    private final ViewGroup proofListContainer;
    private final List<Proof> proofList;
    private final List<CompoundButton> checkBoxes;
    private final ChooseMode chooseMode;
    private OnProofListChangedListener onProofListChangedListener;
    private final List<String> menuItems;
    private OnMenuItemClickListener onMenuItemClickListener;
    private OnOffChainIconClickListener onOffChainIconClickListener;
    private OnProofClickListener onProofClickListener;
    private OnProofRemoveListener onProofRemoveListener;
    private OnPayIconClickListener onPayIconClickListener;
    private OnSignIconClickListener onSignIconClickListener;
    private ActivityResultLauncher<Intent> updateProofLauncher;

    public interface OnProofListChangedListener {
        void onProofListChanged(List<Proof> updatedProofList);
    }

    public interface OnMenuItemClickListener {
        void onMenuItemClick(String menuItem, Proof proof);
    }

    public interface OnOffChainIconClickListener {
        void onOffChainIconClick(Proof proof);
    }

    public interface OnProofClickListener {
        void onProofClick(Proof proof);
    }

    public interface OnProofRemoveListener {
        void onProofRemove(Proof proof);
    }

    public interface OnPayIconClickListener {
        void onSendIconClick(Proof proof);
    }

    public interface OnSignIconClickListener {
        void onSignIconClick(Proof proof);
    }

    public ProofCardContainer(Context context, LinearLayout proofListContainer, ChooseMode chooseMode) {
        this(context, proofListContainer, chooseMode, null);
    }

    public ProofCardContainer(Context context, LinearLayout proofListContainer, ChooseMode chooseMode, List<String> menuItems) {
        this.context = context;
        this.proofListContainer = proofListContainer;
        this.proofList = new ArrayList<>();
        this.checkBoxes = new ArrayList<>();
        this.chooseMode = chooseMode;
        this.menuItems = menuItems;
    }

    public void setOnProofListChangedListener(OnProofListChangedListener listener) {
        this.onProofListChangedListener = listener;
    }

    public void setOnMenuItemClickListener(OnMenuItemClickListener listener) {
        this.onMenuItemClickListener = listener;
    }

    public void setOnOffChainIconClickListener(OnOffChainIconClickListener listener) {
        this.onOffChainIconClickListener = listener;
    }

    public void setOnProofClickListener(OnProofClickListener listener) {
        this.onProofClickListener = listener;
    }

    public void setOnProofRemoveListener(OnProofRemoveListener listener) {
        this.onProofRemoveListener = listener;
    }

    public void setOnPayIconClickListener(OnPayIconClickListener listener) {
        this.onPayIconClickListener = listener;
    }

    public void setOnSignIconClickListener(OnSignIconClickListener listener) {
        this.onSignIconClickListener = listener;
    }

    public void setUpdateProofLauncher(ActivityResultLauncher<Intent> launcher) {
        this.updateProofLauncher = launcher;
    }

    private void notifyProofListChanged() {
        if (onProofListChangedListener != null) {
            onProofListChangedListener.onProofListChanged(new ArrayList<>(proofList));
        }
    }
    @Nullable
    public Map<String, String> getCidMap(List<Proof> proofList,Context context) {
        List<String> fidList = new ArrayList<>();
        for(Proof proof: proofList){
            fidList.add(proof.getIssuer());
        }
        Map<String,String> cidMap = null;
        if(!fidList.isEmpty()){
            CidFidManager cidFidManager = CidFidManager.getInstance(context);
            cidMap = cidFidManager.getCidsByFids(fidList);
        }
        return cidMap;
    }
    public void addProofCard(Proof proof, Map<String, String> cidMap) {
        addProofCardAtPosition(proof, proofList.size(),cidMap);
    }

    private void showProofActivity(Proof proof) {
        android.content.Intent intent = new android.content.Intent(context, com.fc.freer.ui.DetailActivity.class);
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_JSON, proof.toJson());
        intent.putExtra(com.fc.freer.ui.DetailActivity.EXTRA_ENTITY_CLASS, Proof.class.getName());
        context.startActivity(intent);
    }

    public List<Proof> getSelectedProofs() {
        List<Proof> selectedProofs = new ArrayList<>();
        for (int i = 0; i < checkBoxes.size(); i++) {
            if (checkBoxes.get(i).isChecked()) {
                selectedProofs.add(proofList.get(i));
            }
        }
        return selectedProofs;
    }

    public void clearAll() {
        proofList.clear();
        proofListContainer.removeAllViews();
        checkBoxes.clear();
    }

    public List<Proof> getProofList() {
        return proofList;
    }

    public void selectAll(boolean selected) {
        if (chooseMode != ChooseMode.CHOOSE_MULTI) {
            return; // Do nothing for non-checkbox modes or single choice mode
        }

        for (CompoundButton checkBox : checkBoxes) {
            checkBox.setChecked(selected);
        }
    }

    public boolean areAllSelected() {
        if (chooseMode != ChooseMode.CHOOSE_MULTI || checkBoxes.isEmpty()) {
            return false;
        }

        for (CompoundButton checkBox : checkBoxes) {
            if (!checkBox.isChecked()) {
                return false;
            }
        }
        return true;
    }

    public boolean areNoneSelected() {
        if (chooseMode != ChooseMode.CHOOSE_MULTI || checkBoxes.isEmpty()) {
            return true;
        }

        for (CompoundButton checkBox : checkBoxes) {
            if (checkBox.isChecked()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes all currently selected proofs from the container
     */
    public void removeSelectedProofs() {
        if (chooseMode != ChooseMode.CHOOSE_MULTI || checkBoxes.isEmpty()) {
            return;
        }

        // Get indices of selected items (in reverse order to avoid index shifting)
        List<Integer> selectedIndices = new ArrayList<>();
        for (int i = checkBoxes.size() - 1; i >= 0; i--) {
            if (checkBoxes.get(i).isChecked()) {
                selectedIndices.add(i);
            }
        }

        // Remove selected items
        for (int index : selectedIndices) {
            if (index < proofListContainer.getChildCount()) {
                proofListContainer.removeViewAt(index);
            }
            if (index < proofList.size()) {
                proofList.remove(index);
            }
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }

        notifyProofListChanged();
    }

    /**
     * Removes cards from the beginning of the list (newest/latest cards)
     * @param count Number of cards to remove from the beginning
     */
    public void removeFromBeginning(int count) {
        if (count <= 0 || count > proofList.size()) {
            return;
        }

        // Remove views from the beginning
        for (int i = 0; i < count; i++) {
            proofListContainer.removeViewAt(0);
        }

        // Remove from lists
        for (int i = 0; i < count; i++) {
            proofList.remove(0);
            if (!checkBoxes.isEmpty()) {
                checkBoxes.remove(0);
            }
        }

        notifyProofListChanged();
    }

    /**
     * Removes cards from the end of the list (oldest/earliest cards)
     * @param count Number of cards to remove from the end
     */
    public void removeFromEnd(int count) {
        if (count <= 0 || count > proofList.size()) {
            return;
        }

        int size = proofList.size();
        // Remove views from the end
        for (int i = 0; i < count; i++) {
            proofListContainer.removeViewAt(proofListContainer.getChildCount() - 1);
        }

        // Remove from lists
        for (int i = 0; i < count; i++) {
            proofList.remove(size - 1 - i);
            if (checkBoxes.size() > size - 1 - i) {
                checkBoxes.remove(size - 1 - i);
            }
        }

        notifyProofListChanged();
    }

    /**
     * Adds proof cards to the beginning of the list (newest/latest position)
     * @param proofsToAdd List of proof objects to add at the beginning
     */
    public void addProofCardsToBeginning(List<Proof> proofsToAdd) {
        if (proofsToAdd == null || proofsToAdd.isEmpty()) {
            return;
        }
        Map<String, String> cidMap = getCidMap(proofsToAdd,context);
        // Add in reverse order so they appear in correct order at the beginning
        for (int i = proofsToAdd.size() - 1; i >= 0; i--) {
            Proof proof = proofsToAdd.get(i);
            addProofCardAtPosition(proof, 0, cidMap);
        }
    }

    public void addProofCardsToBeginningWithBottomRemoval(List<Proof> proofsToAdd, int itemsToRemoveFromEnd, int maxSize) {
        if (proofsToAdd == null || proofsToAdd.isEmpty()) {
            return;
        }

        addProofCardsToBeginning(proofsToAdd);

        int currentSize = proofList.size();
        int removeCount = Math.max(itemsToRemoveFromEnd, currentSize - maxSize);
        if (removeCount > 0) {
            removeFromEnd(removeCount);
        } else {
            notifyProofListChanged();
        }
    }

    public void addProofCardsToEndWithTopRemoval(List<Proof> proofsToAdd, int itemsToRemoveFromBeginning, int maxSize) {
        if (proofsToAdd == null || proofsToAdd.isEmpty()) {
            return;
        }

        Map<String, String> cidMap = getCidMap(proofsToAdd, context);
        for (Proof proof : proofsToAdd) {
            addProofCardAtPosition(proof, proofList.size(), cidMap);
        }

        int currentSize = proofList.size();
        int removeCount = Math.max(itemsToRemoveFromBeginning, currentSize - maxSize);
        if (removeCount > 0) {
            removeFromBeginning(removeCount);
        } else {
            notifyProofListChanged();
        }
    }

    public void sortByTitle(boolean ascending, boolean enableSort) {
        sortProofs(enableSort, (pair1, pair2) -> {
            String title1 = pair1.proof.getTitle();
            String title2 = pair2.proof.getTitle();
            return compareNullable(title1, title2, ascending);
        });
    }

    public void sortByIssuer(boolean ascending, boolean enableSort) {
        sortProofs(enableSort, (pair1, pair2) -> {
            String issuer1 = pair1.proof.getIssuer();
            String issuer2 = pair2.proof.getIssuer();
            return compareNullable(issuer1, issuer2, ascending);
        });
    }

    public void sortByLastTime(boolean ascending, boolean enableSort) {
        sortProofs(enableSort, (pair1, pair2) -> {
            Long lastTime1 = pair1.proof.getLastTime();
            Long lastTime2 = pair2.proof.getLastTime();
            return compareNullable(lastTime1, lastTime2, ascending);
        });
    }

    public void sortByUpdateHeight(boolean ascending, boolean enableSort) {
        sortProofs(enableSort, (pair1, pair2) -> {
            Long updateHeight1 = pair1.proof.getLastHeight();
            Long updateHeight2 = pair2.proof.getLastHeight();
            return compareNullable(updateHeight1, updateHeight2, ascending);
        });
    }

    private void addProofCardAtPosition(Proof proof, int position, Map<String, String> cidMap) {
        View cardView = createCardView();
        CompoundButton checkBox = setupCardViewInteractions(cardView, proof,cidMap);

        proofListContainer.addView(cardView, position);
        proofList.add(position, proof);
        if (checkBox != null) {
            checkBoxes.add(position, checkBox);
        }
    }

    private View createCardView() {
        int layoutResId = getLayoutResId();
        return LayoutInflater.from(context).inflate(layoutResId, proofListContainer, false);
    }

    private int getLayoutResId() {
        return R.layout.item_proof_card;
    }

    private CompoundButton setupCardViewInteractions(View cardView, Proof proof, Map<String, String> cidMap) {
        CompoundButton checkBox = setupCheckBox(cardView);
        setupCardData(cardView, proof,cidMap);
        setupClickListeners(cardView, proof);
        setupButtons(cardView, proof);
        return checkBox;
    }

    private CompoundButton setupCheckBox(View cardView) {
        if (chooseMode == ChooseMode.CHOOSE_ONE_RETURN || chooseMode == ChooseMode.WITHOUT_CHOOSE) {
            return null;
        }

        CompoundButton checkBox = cardView.findViewById(R.id.proof_checkbox);
        checkBox.setVisibility(VISIBLE);
        if (chooseMode == ChooseMode.CHOOSE_ONE) {
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) {
                    for (CompoundButton cb : checkBoxes) {
                        if (cb != checkBox) {
                            cb.setChecked(false);
                        }
                    }
                }
            });
        } else {
            checkBox.setOnCheckedChangeListener((buttonView, isChecked) -> notifyProofListChanged());
        }
        return checkBox;
    }

    private void setupCardData(View cardView, Proof proof, Map<String, String> cidMap) {
        ImageView issuerAvatarView = cardView.findViewById(R.id.proof_issuer_avatar);
        ImageView ownerAvatarView = cardView.findViewById(R.id.proof_owner_avatar);
        TextView titleValue = cardView.findViewById(R.id.proof_title_value);
        TextView issuerValue = cardView.findViewById(R.id.proof_issuer_value);
        TextView idValue = cardView.findViewById(R.id.proof_id_value);
        TextView lastTimeValue = cardView.findViewById(R.id.proof_last_time_value);
        ImageView onChainIcon = cardView.findViewById(R.id.proof_on_chain_icon);
        ImageView sendIcon = cardView.findViewById(R.id.proof_send_icon);
        ImageView signIcon = cardView.findViewById(R.id.proof_sign_icon);

        setupAvatar(issuerAvatarView, proof.getIssuer());
        setupAvatar(ownerAvatarView, proof.getOwner());
        setTextValue(titleValue, proof.getTitle());

        if(cidMap!=null && cidMap.get(proof.getIssuer())!=null)
            setTextValue(issuerValue, cidMap.get(proof.getIssuer()));
        else setTextValue(issuerValue, proof.getIssuer());

        setTextValue(idValue, proof.getId());
        setLastTimeValue(lastTimeValue, proof.getLastTime());
        setupOnChainIcon(onChainIcon, proof);
        setupPayIcon(sendIcon, proof);
        setupSignIcon(signIcon, proof);
    }

    private void setupAvatar(ImageView avatarView, String fid) {
        if (fid != null && !fid.isEmpty()) {
            try {
                AvatarManager avatarManager = AvatarManager.getInstance(context);
                Bitmap avatarBitmap = avatarManager.getAvatarBitmap(fid);
                if (avatarBitmap != null) {
                    avatarView.setImageBitmap(avatarBitmap);
                } else {
                    avatarView.setImageResource(R.drawable.container_outline);
                }
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to load avatar for FID %s: %s", fid, e.getMessage());
                avatarView.setImageResource(R.drawable.container_outline);
            }
        } else {
            avatarView.setImageResource(R.drawable.container_outline);
        }
    }

    private void setTextValue(TextView textView, String value) {
        textView.setText(value != null ? value : "");
    }

    private void setLastTimeValue(TextView lastTimeValue, Long lastTime) {
        if (lastTime != null) {
            try {
                // Convert Unix timestamp to readable date
                Date date = new Date(lastTime * 1000L); // Convert seconds to milliseconds
                SimpleDateFormat sdf = new SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault());
                String formattedDate = sdf.format(date);
                lastTimeValue.setText(formattedDate);
            } catch (Exception e) {
                TimberLogger.e(TAG, "Failed to format last time: %s", e.getMessage());
                lastTimeValue.setText(String.valueOf(lastTime));
            }
        } else {
            lastTimeValue.setText("");
        }
    }

    private void setupOnChainIcon(ImageView onChainIcon, Proof proof) {
        Boolean onChain = proof.getOnChain();
        if (onChain != null && onChain) {
            onChainIcon.setImageResource(R.drawable.ic_on_chain);
        } else if (onChain != null) {
            onChainIcon.setImageResource(R.drawable.ic_off_chain);
            onChainIcon.setOnClickListener(v -> {
                if (onOffChainIconClickListener != null) {
                    onOffChainIconClickListener.onOffChainIconClick(proof);
                }
            });
        } else {
            onChainIcon.setImageResource(R.drawable.ic_on_chain_unknown);
        }
    }

    private void setupPayIcon(ImageView payIcon, Proof proof) {
        if (payIcon == null) return;

        // Get living FID from current setting
        String livingFid = null;
        try {
            if (SettingManager.getInstance().getCurrentKeyInfo() != null) {
                livingFid = SettingManager.getInstance().getCurrentKeyInfo().getId();
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to get living FID: %s", e.getMessage());
        }

        // Show pay icon if: proof.onChain is true, owner is living FID, transferable is true, and active is true
        Boolean onChain = proof.getOnChain();
        if (Boolean.TRUE.equals(onChain)
                && livingFid != null && livingFid.equals(proof.getOwner())
                && Boolean.TRUE.equals(proof.isTransferable())
                && Boolean.TRUE.equals(proof.isActive())
                && (proof.getCosignersInvited() == null || (proof.getCosignersSigned()!=null && proof.getCosignersInvited().size()== proof.getCosignersSigned().size()))
        ) {
            payIcon.setVisibility(View.VISIBLE);
            payIcon.setOnClickListener(v -> {
                if (onPayIconClickListener != null) {
                    onPayIconClickListener.onSendIconClick(proof);
                }
            });
        } else {
            payIcon.setVisibility(View.GONE);
        }
    }

    private void setupSignIcon(ImageView signIcon, Proof proof) {
        if (signIcon == null) return;

        // Get living FID from current setting
        String livingFid = null;
        try {
            if (SettingManager.getInstance().getCurrentKeyInfo() != null) {
                livingFid = SettingManager.getInstance().getCurrentKeyInfo().getId();
            }
        } catch (Exception e) {
            TimberLogger.e(TAG, "Failed to get living FID: %s", e.getMessage());
        }

        // Show sign icon if cosignersInvited contains living FID
        if (livingFid != null
                && Boolean.TRUE.equals(proof.getOnChain())
                && proof.getCosignersInvited() != null
                && proof.getCosignersInvited().contains(livingFid)
                && (proof.getCosignersSigned() == null || !proof.getCosignersSigned().contains(livingFid))
        ) {
            signIcon.setVisibility(View.VISIBLE);

            // Set color to error if living FID is not in cosignersSigned
            if (proof.getCosignersSigned() == null || !proof.getCosignersSigned().contains(livingFid)) {
                // Set tint to error color
                int errorColor = ContextCompat.getColor(context, R.color.error);
                ImageViewCompat.setImageTintList(signIcon, ColorStateList.valueOf(errorColor));
            } else {
                // Clear tint (use original icon color)
                ImageViewCompat.setImageTintList(signIcon, null);
            }

            // Set up click listener for sign icon
            signIcon.setOnClickListener(v -> {
                if (onSignIconClickListener != null) {
                    onSignIconClickListener.onSignIconClick(proof);
                }
            });
        } else {
            signIcon.setVisibility(View.GONE);
        }
    }

    private void setupClickListeners(View cardView, Proof proof) {
        TextView titleValue = cardView.findViewById(R.id.proof_title_value);
        TextView issuerValue = cardView.findViewById(R.id.proof_issuer_value);
        TextView idValue = cardView.findViewById(R.id.proof_id_value);
        TextView lastTimeValue = cardView.findViewById(R.id.proof_last_time_value);

        // Use onProofClickListener if set (for direct selection mode), otherwise show detail activity
        View.OnClickListener clickListener = v -> {
            if (onProofClickListener != null) {
                onProofClickListener.onProofClick(proof);
            } else {
                showProofActivity(proof);
            }
        };
        View.OnLongClickListener longPressListener = createLongPressListener(cardView, proof);

        cardView.setOnClickListener(clickListener);
        cardView.setOnLongClickListener(longPressListener);

        if (titleValue != null) {
            titleValue.setOnClickListener(clickListener);
            titleValue.setOnLongClickListener(longPressListener);
        }

        if (issuerValue != null) {
            issuerValue.setOnClickListener(clickListener);
            issuerValue.setOnLongClickListener(longPressListener);
        }

        if (idValue != null) {
            idValue.setOnClickListener(clickListener);
            idValue.setOnLongClickListener(longPressListener);
        }

        if (lastTimeValue != null) {
            lastTimeValue.setOnClickListener(clickListener);
            lastTimeValue.setOnLongClickListener(longPressListener);
        }
    }

    private View.OnLongClickListener createLongPressListener(View cardView, Proof proof) {
        return v -> {
            if (menuItems != null && !menuItems.isEmpty()) {
                PopupMenu popup = new PopupMenu(context, v);
                for (String menuItem : menuItems) {
                    popup.getMenu().add(menuItem);
                }

                popup.setOnMenuItemClickListener(item -> {
                    if(item.getTitle()==null)return false;
                    String title = item.getTitle().toString();
                    if ("Delete".equals(title)) {
                        removeProofCard(cardView, proof);
                        return true;
                    } else if (onMenuItemClickListener != null) {
                        onMenuItemClickListener.onMenuItemClick(title, proof);
                        return true;
                    }
                    return false;
                });

                popup.show();
                return true;
            }
            return false;
        };
    }

    private void removeProofCard(View cardView, Proof proof) {
        proofListContainer.removeView(cardView);
        int index = proofList.indexOf(proof);
        if (index != -1) {
            proofList.remove(index);
            if (index < checkBoxes.size()) {
                checkBoxes.remove(index);
            }
        }
        notifyProofListChanged();
    }

    /**
     * Removes a specific proof from the container by proof ID
     * @param proofId ID of the proof to remove
     * @return true if proof was found and removed, false otherwise
     */
    public boolean removeProofById(String proofId) {
        if (proofId == null) return false;

        for (int i = 0; i < proofList.size(); i++) {
            Proof proof = proofList.get(i);
            if (proofId.equals(proof.getId())) {
                // Remove view
                if (i < proofListContainer.getChildCount()) {
                    proofListContainer.removeViewAt(i);
                }
                // Remove from lists
                proofList.remove(i);
                if (i < checkBoxes.size()) {
                    checkBoxes.remove(i);
                }
                notifyProofListChanged();
                return true;
            }
        }
        return false;
    }

    /**
     * Updates a specific proof card in the container by finding it by ID and refreshing its view
     * @param updatedProof The proof object with updated data
     * @return true if proof was found and updated, false otherwise
     */
    public boolean updateProofCard(Proof updatedProof) {
        if (updatedProof == null || updatedProof.getId() == null) return false;

        for (int i = 0; i < proofList.size(); i++) {
            Proof proof = proofList.get(i);
            if (updatedProof.getId().equals(proof.getId())) {
                // Update the proof in the list
                proofList.set(i, updatedProof);

                // Get the existing card view
                View cardView = proofListContainer.getChildAt(i);
                if (cardView != null) {
                    // Re-setup card data with updated proof
                    Map<String, String> cidMap = getCidMap(java.util.Collections.singletonList(updatedProof), context);
                    setupCardData(cardView, updatedProof, cidMap);
                    setupButtons(cardView, updatedProof);
                }

                notifyProofListChanged();
                return true;
            }
        }
        return false;
    }

    private void setupButtons(View cardView, Proof proof) {
        ImageButton editButton = cardView.findViewById(R.id.proof_edit_button);

        if (editButton != null) {
            // Hide edit icon if proof.onChain is not false (i.e., if it's true or null)
            Boolean onChain = proof.getOnChain();
            if (!Boolean.FALSE.equals(onChain)) {
                editButton.setVisibility(View.GONE);
                return;
            }

            // Show edit icon for off-chain proofs
            editButton.setVisibility(View.VISIBLE);

            if (chooseMode == ChooseMode.WITHOUT_CHOOSE) {
                // Change icon to clear/remove icon
                editButton.setImageResource(R.drawable.ic_clear);
                editButton.setOnClickListener(v -> {
                    // Remove the proof from the card list
                    removeProofCard(cardView, proof);
                    // Notify via callback if set
                    if (onProofRemoveListener != null) {
                        onProofRemoveListener.onProofRemove(proof);
                    }
                });
            } else {
                editButton.setOnClickListener(v -> {
                    // Launch IssueProofActivity with proof data for editing
                    android.content.Intent intent = new android.content.Intent(context, com.fc.freer.home.IssueProofActivity.class);
                    intent.putExtra(com.fc.freer.home.IssueProofActivity.EXTRA_PROOF_JSON, proof.toJson());
                    if (updateProofLauncher != null) {
                        updateProofLauncher.launch(intent);
                    } else {
                        context.startActivity(intent);
                    }
                });
            }
        }
    }

    private void sortProofs(boolean enableSort, Comparator<ProofViewPair> comparator) {
        if (proofList.isEmpty() || !enableSort) {
            return;
        }

        List<ProofViewPair> pairs = createProofViewPairs();
        pairs.sort(comparator);
        updateListsFromPairs(pairs);
    }

    private List<ProofViewPair> createProofViewPairs() {
        List<ProofViewPair> pairs = new ArrayList<>();
        for (int i = 0; i < proofList.size(); i++) {
            Proof proof = proofList.get(i);
            View cardView = proofListContainer.getChildAt(i);
            CompoundButton checkBox = (i < checkBoxes.size()) ? checkBoxes.get(i) : null;
            pairs.add(new ProofViewPair(proof, cardView, checkBox));
        }
        return pairs;
    }

    private void updateListsFromPairs(List<ProofViewPair> pairs) {
        proofList.clear();
        checkBoxes.clear();
        proofListContainer.removeAllViews();

        for (ProofViewPair pair : pairs) {
            proofList.add(pair.proof);
            if (pair.checkBox != null) {
                checkBoxes.add(pair.checkBox);
            }
            proofListContainer.addView(pair.cardView);
        }
    }

    private <T extends Comparable<T>> int compareNullable(T value1, T value2, boolean ascending) {
        if (value1 == null && value2 == null) return 0;
        if (value1 == null) return 1;
        if (value2 == null) return -1;
        return ascending ? value1.compareTo(value2) : value2.compareTo(value1);
    }

    private record ProofViewPair(Proof proof, View cardView, CompoundButton checkBox) {
    }
}
