package com.fc.freer.utils;

import android.content.Intent;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.data.DataActivity;
import com.fc.freer.im.SearchFidsOnChainActivity;
import com.fc.freer.ui.IoIconsView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Wires the input-field icons that need to launch a chooser:
 * <ul>
 *     <li>the people icon of a FID field opens {@link SearchFidsOnChainActivity} to search FID(s) on
 *     chain (that screen also lets the user pick from contacts);</li>
 *     <li>the file icon of a DID field opens {@link DataActivity} to pick a local data DID.</li>
 * </ul>
 * The chosen value is written back into the target input. A single instance registers the two
 * activity-result launchers once and can serve every field of an activity, remembering which field
 * triggered the current chooser so the result lands in the right place.
 *
 * <p>Construct this from the owning activity's {@code onCreate} (after {@code super.onCreate}) so the
 * launchers are registered before the activity is started.</p>
 */
public class EntityFieldPickers {
    private final BaseCryptoActivity activity;
    private final ActivityResultLauncher<Intent> fidSearchLauncher;
    private final ActivityResultLauncher<Intent> dataLauncher;
    private final ActivityResultLauncher<Intent> idLauncher;

    // The field awaiting the current chooser result.
    private TextInputEditText fidSearchTarget;
    private boolean fidSearchAppend;
    private TextInputEditText didTarget;
    private TextInputEditText idTarget;
    private boolean idAppend;

    public EntityFieldPickers(BaseCryptoActivity activity) {
        this.activity = activity;
        fidSearchLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == BaseCryptoActivity.RESULT_OK
                            && result.getData() != null && fidSearchTarget != null) {
                        onFidsSearched(result.getData());
                    }
                });
        dataLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == BaseCryptoActivity.RESULT_OK
                            && result.getData() != null && didTarget != null) {
                        String did = result.getData().getStringExtra(DataActivity.EXTRA_SELECTED_DID);
                        if (did != null && !did.isEmpty()) {
                            didTarget.setText(did);
                        }
                    }
                });
        idLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == BaseCryptoActivity.RESULT_OK
                            && result.getData() != null && idTarget != null) {
                        onIdsChosen(result.getData());
                    }
                });
    }

    /**
     * Show the people icon on a FID field. Tapping it opens {@link SearchFidsOnChainActivity} so the
     * user can search FIDs on chain (and still pick from contacts inside that screen). When
     * {@code multi} is true the chooser opens in multi-select mode and the chosen FIDs are appended
     * (comma separated) to the field; otherwise a single FID replaces the field content. Scan and
     * paste stay available; the file icon stays hidden.
     */
    public void bindFidField(int viewId, int iconId, int qrRequestCode, TextInputEditText input, boolean multi) {
        TextIconsUtils.setupTextIcons(activity, viewId, iconId, qrRequestCode, true, false,
                isSingle -> searchFidsInto(input, multi), null);
    }

    /**
     * Show the file icon on a DID field so the user can pick a local data DID from {@link DataActivity}.
     * The DID can still be typed, scanned or pasted manually. The people icon stays hidden.
     */
    public void bindDidField(int viewId, int iconId, int qrRequestCode, TextInputEditText input) {
        TextIconsUtils.setupTextIcons(activity, viewId, iconId, qrRequestCode, false, true,
                null, () -> pickDidInto(input));
    }

    /**
     * Launch {@link SearchFidsOnChainActivity} and route the chosen FID(s) into {@code input}. When
     * {@code multi} is true the chosen FIDs are appended (comma separated); otherwise a single FID
     * replaces the field content.
     */
    public void searchFidsInto(TextInputEditText input, boolean multi) {
        fidSearchTarget = input;
        fidSearchAppend = multi;
        Intent intent = new Intent(activity, SearchFidsOnChainActivity.class);
        intent.putExtra(SearchFidsOnChainActivity.EXTRA_CHOOSE_MODE,
                (multi ? ChooseMode.CHOOSE_MULTI : ChooseMode.CHOOSE_ONE_RETURN).name());
        fidSearchLauncher.launch(intent);
    }

    /** Launch the data (DID) chooser and route the single chosen DID into {@code input}. */
    public void pickDidInto(TextInputEditText input) {
        didTarget = input;
        Intent intent = new Intent(activity, DataActivity.class);
        intent.putExtra(DataActivity.EXTRA_CHOOSE_MODE, ChooseMode.CHOOSE_ONE.name());
        dataLauncher.launch(intent);
    }

    /**
     * Show the file icon on an entity-id field (protocols/services/codes) so the user can pick
     * id(s) from the matching list activity. When {@code multi} is true the chosen ids are appended
     * (comma separated); otherwise a single id replaces the field content. The id can still be typed,
     * scanned or pasted manually.
     *
     * @param listActivityClass ProtocolActivity/ServiceActivity/CodeActivity — the list to open.
     */
    public void bindEntityIdField(int viewId, int iconId, int qrRequestCode, TextInputEditText input,
                                  boolean multi, Class<?> listActivityClass) {
        TextIconsUtils.setupTextIcons(activity, viewId, iconId, qrRequestCode, false, true,
                null, () -> pickIdsInto(input, multi, listActivityClass));
        // Repurpose the file button as a "search the list" trigger for entity-id fields.
        View container = activity.findViewById(viewId);
        if (container != null) {
            IoIconsView icons = container.findViewById(iconId);
            if (icons != null) {
                icons.setFileIcon(R.drawable.ic_search);
                icons.setFileIconTint(activity.getResources().getColor(R.color.accent, activity.getTheme()));
            }
        }
    }

    /** Launch the given list activity as a picker and route the chosen id(s) into {@code input}. */
    public void pickIdsInto(TextInputEditText input, boolean multi, Class<?> listActivityClass) {
        idTarget = input;
        idAppend = multi;
        Intent intent = new Intent(activity, listActivityClass);
        intent.putExtra(EntityChooser.EXTRA_CHOOSE_MODE,
                (multi ? ChooseMode.CHOOSE_MULTI : ChooseMode.CHOOSE_ONE).name());
        idLauncher.launch(intent);
    }

    private void onIdsChosen(Intent data) {
        List<String> ids = new ArrayList<>();
        ArrayList<String> multi = data.getStringArrayListExtra(EntityChooser.EXTRA_SELECTED_IDS);
        if (multi != null) {
            ids.addAll(multi);
        } else {
            String single = data.getStringExtra(EntityChooser.EXTRA_SELECTED_ID);
            if (single != null) ids.add(single);
        }
        if (ids.isEmpty()) return;
        if (idAppend) {
            appendCsv(idTarget, ids);
        } else {
            idTarget.setText(ids.get(0));
        }
    }

    private void onFidsSearched(Intent data) {
        ArrayList<String> fids = data.getStringArrayListExtra(SearchFidsOnChainActivity.EXTRA_SELECTED_FIDS);
        if (fids == null || fids.isEmpty()) return;
        if (fidSearchAppend) {
            appendCsv(fidSearchTarget, fids);
        } else {
            fidSearchTarget.setText(fids.get(0));
        }
    }

    /** Append comma-separated values to {@code input}, preserving order and dropping duplicates. */
    private static void appendCsv(TextInputEditText input, List<String> values) {
        Set<String> set = new LinkedHashSet<>();
        String existing = input.getText() != null ? input.getText().toString() : "";
        for (String part : existing.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) set.add(trimmed);
        }
        set.addAll(values);
        input.setText(String.join(", ", set));
    }
}
