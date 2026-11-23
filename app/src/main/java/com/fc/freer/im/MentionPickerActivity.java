package com.fc.freer.im;

import static com.fc.fc_ajdk.constants.FieldNames.DESC;
import static com.fc.fc_ajdk.constants.FieldNames.FREER;
import static com.fc.fc_ajdk.constants.FieldNames.ID;
import static com.fc.fc_ajdk.constants.FieldNames.LAST_HEIGHT;
import static com.fc.fc_ajdk.constants.FieldNames.USED_CIDS;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.R;
import com.fc.freer.contact.ContactLabel;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.nobody.NobodyUi;
import com.fc.freer.utils.ApiCenter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Whom an {@code @} names: the group's members, searchable. FreerForMac's and the iPhone's
 * MentionPickerSheet, the same rules.
 *
 * <p>A group can have hundreds of members and a square thousands, so nothing here is done for
 * all of them at once:
 * <ul>
 *   <li><b>The list is paged</b>, as the member list is: {@link #PAGE_SIZE} rows, growing as the
 *   end comes near, and only the rows shown are named — one {@code freerByIds} per page.</li>
 *   <li><b>The order puts the likely first</b>: you (an {@code @} of yourself flags a message for
 *   your other devices), then whoever spoke last, then the rest — see
 *   {@link MentionCenter#candidates}.</li>
 *   <li><b>The search covers what is not yet named.</b> It matches at once on FIDs, the CIDs
 *   already known and the address book's labels; in a group larger than a page it also asks the
 *   chain, which searches every CID a FID has had, and keeps the hits that are members.</li>
 * </ul>
 *
 * <p>The search box is at the foot, by the thumb, and does not take the keyboard by itself: most
 * picks are a glance and a tap. Picking a row returns its handle; Back keeps the {@code @} as
 * typed.
 */
public class MentionPickerActivity extends BaseCryptoActivity {
    private static final String TAG = "MentionPickerActivity";

    public static final String EXTRA_FIDS = "extra_fids";
    public static final String EXTRA_LIVE_FID = "extra_live_fid";
    /** In the result: the picked FID, and what to write after the {@code @} for it. */
    public static final String RESULT_FID = "result_fid";
    public static final String RESULT_HANDLE = "result_handle";

    private static final int PAGE_SIZE = 50;
    private static final int PREFETCH_DISTANCE = 10;
    private static final long SEARCH_PAUSE_MS = 400;

    private final List<String> allFids = new ArrayList<>();
    private final List<String> matching = new ArrayList<>();
    private final List<String> shown = new ArrayList<>();
    /** CIDs the chain search turned up, for members not yet named. */
    private final Map<String, String> foundCids = new HashMap<>();
    /** Members the chain search matched for {@link #searchedTerm}. */
    private final Set<String> chainHits = new HashSet<>();
    private final Set<String> asked = new HashSet<>();
    private String searchedTerm = "";
    private String liveFid;
    private String term = "";
    private int searchGeneration;

    private RecyclerView recyclerView;
    private TextView countText;
    private TextView emptyText;
    private ProgressBar searching;
    private EditText searchInput;
    private ImageView clearSearch;
    private MentionAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pendingSearch;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_mention_picker;
    }

    @Override
    protected String getActivityTitle() {
        return getString(R.string.im_mention);
    }

    @Override
    protected void initializeViews() {
        // The keyboard waits to be asked for.
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        String[] fids = getIntent().getStringArrayExtra(EXTRA_FIDS);
        liveFid = getIntent().getStringExtra(EXTRA_LIVE_FID);
        if (fids != null) allFids.addAll(Arrays.asList(fids));

        recyclerView = findViewById(R.id.mention_recycler_view);
        countText = findViewById(R.id.mention_count);
        emptyText = findViewById(R.id.mention_empty);
        searching = findViewById(R.id.mention_searching);
        searchInput = findViewById(R.id.mention_search);
        clearSearch = findViewById(R.id.mention_search_clear);

        adapter = new MentionAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy <= 0) return;
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm == null) return;
                if (lm.findLastVisibleItemPosition() >= shown.size() - PREFETCH_DISTANCE
                        && shown.size() < matching.size()) {
                    appendPage();
                }
            }
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                term = s.toString().trim();
                clearSearch.setVisibility(term.isEmpty() ? View.GONE : View.VISIBLE);
                refilter();
                scheduleChainSearch();
            }
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && matching.size() == 1) {
                pick(matching.get(0));
                return true;
            }
            return false;
        });
        clearSearch.setOnClickListener(v -> searchInput.setText(""));

        NobodyUi.observe(this, learned -> adapter.notifyDataSetChanged());
        ContactLabel.observe(this, () -> adapter.notifyDataSetChanged());
        refilter();
    }

    @Override
    protected void setupButtons() {
        ImageButton back = findViewById(R.id.back_button);
        back.setOnClickListener(v -> finish());
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
    }

    private static String cachedCid(String fid) {
        CidFidManager cids = CidFidManager.getInstance();
        return cids != null ? cids.getCidByFid(fid) : null;
    }

    private String cidOf(String fid) {
        String cid = cachedCid(fid);
        return cid != null && !cid.isEmpty() ? cid : foundCids.get(fid);
    }

    private static boolean contains(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** The members the search leaves, in the list's order, and the first page of them. */
    private void refilter() {
        matching.clear();
        if (term.isEmpty()) {
            matching.addAll(allFids);
        } else {
            String needle = term.toLowerCase(Locale.ROOT);
            boolean useHits = term.equals(searchedTerm);
            for (String fid : allFids) {
                if ((useHits && chainHits.contains(fid))
                        || contains(fid, needle)
                        || contains(cidOf(fid), needle)
                        || contains(ContactLabel.of(fid), needle)) {
                    matching.add(fid);
                }
            }
        }
        shown.clear();
        adapter.notifyDataSetChanged();
        appendPage();
        countText.setText(term.isEmpty()
                ? getString(R.string.im_mention_count, allFids.size())
                : getString(R.string.im_mention_count_filtered, matching.size(), allFids.size()));
        emptyText.setVisibility(matching.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void appendPage() {
        int start = shown.size();
        int end = Math.min(start + PAGE_SIZE, matching.size());
        if (end <= start) return;
        List<String> page = new ArrayList<>(matching.subList(start, end));
        shown.addAll(page);
        adapter.notifyItemRangeInserted(start, page.size());
        nameAll(page);
    }

    /** One directory call for a page's unnamed rows, rather than one per row. */
    private void nameAll(List<String> page) {
        List<String> wanted = new ArrayList<>();
        for (String fid : page) {
            if (cidOf(fid) == null && asked.add(fid)) wanted.add(fid);
        }
        if (wanted.isEmpty()) return;
        NobodyUi.resolveAsync(wanted);
        new Thread(() -> {
            FapiClient client = fapiClient();
            if (client == null) return;
            try {
                Map<String, Freer> found = client.freerByIds(wanted);
                if (found == null || found.isEmpty()) return;
                CidFidManager cids = CidFidManager.getInstance();
                for (Map.Entry<String, Freer> e : found.entrySet()) {
                    String cid = e.getValue() != null ? e.getValue().getCid() : null;
                    if (cid != null && !cid.isEmpty() && cids != null) cids.add(e.getKey(), cid);
                }
                runOnUiThread(() -> adapter.notifyDataSetChanged());
            } catch (Exception e) {
                TimberLogger.e(TAG, "Naming a page failed: %s", e.getMessage());
            }
        }).start();
    }

    private static FapiClient fapiClient() {
        ApiCenter apiCenter = ApiCenter.getInstance();
        return apiCenter != null
                ? (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7) : null;
    }

    /**
     * Ask the chain for members whose CID — any they have had — contains the term. Only where
     * the list is longer than a page, so most of its names are not known here; and after a pause
     * in the typing, so a name typed fast costs one search and not six.
     */
    private void scheduleChainSearch() {
        if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
        final int generation = ++searchGeneration;
        final String wanted = term;
        if (wanted.length() < 2 || allFids.size() <= PAGE_SIZE) {
            searching.setVisibility(View.GONE);
            return;
        }
        pendingSearch = () -> {
            searching.setVisibility(View.VISIBLE);
            Set<String> members = new HashSet<>(allFids);
            new Thread(() -> {
                Set<String> hits = new HashSet<>();
                Map<String, String> cids = new HashMap<>();
                FapiClient client = fapiClient();
                List<String> after = null;
                // A few pages of the answer: a common word matches many FIDs not in this group.
                for (int round = 0; client != null && round < 5 && generation == searchGeneration; round++) {
                    try {
                        Fcdsl fcdsl = new Fcdsl();
                        fcdsl.addNewQuery().addNewPart().addNewFields(ID, USED_CIDS).addNewValue(wanted);
                        fcdsl.setSize("100");
                        fcdsl.addSort(LAST_HEIGHT, DESC).addSort(ID, DESC);
                        if (after != null) fcdsl.setAfter(after);
                        List<Freer> results = client.entitySearch(FREER, fcdsl, Freer.class);
                        if (results == null || results.isEmpty()) break;
                        for (Freer f : results) {
                            if (f.getId() == null || !members.contains(f.getId())) continue;
                            hits.add(f.getId());
                            if (f.getCid() != null && !f.getCid().isEmpty()) cids.put(f.getId(), f.getCid());
                        }
                        after = client.getLastResponse() != null ? client.getLastResponse().getLast() : null;
                        if (after == null || after.isEmpty() || results.size() < 100) break;
                    } catch (Exception e) {
                        TimberLogger.e(TAG, "Member search failed: %s", e.getMessage());
                        break;
                    }
                }
                runOnUiThread(() -> {
                    if (generation != searchGeneration || isFinishing()) return;
                    searching.setVisibility(View.GONE);
                    foundCids.putAll(cids);
                    chainHits.clear();
                    chainHits.addAll(hits);
                    searchedTerm = wanted;
                    refilter();
                });
            }).start();
        };
        handler.postDelayed(pendingSearch, SEARCH_PAUSE_MS);
    }

    private void pick(String fid) {
        String cid = cidOf(fid);
        Intent result = new Intent();
        result.putExtra(RESULT_FID, fid);
        result.putExtra(RESULT_HANDLE, cid != null && !cid.isEmpty() ? cid : fid);
        setResult(RESULT_OK, result);
        finish();
    }

    @Override
    protected void onDestroy() {
        if (pendingSearch != null) handler.removeCallbacks(pendingSearch);
        searchGeneration++;
        super.onDestroy();
    }

    private class MentionAdapter extends RecyclerView.Adapter<MentionAdapter.Holder> {

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_member, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(shown.get(position));
        }

        @Override
        public int getItemCount() {
            return shown.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            private final ImageView avatar;
            private final TextView cidText;
            private final TextView fidText;

            Holder(@NonNull View itemView) {
                super(itemView);
                avatar = itemView.findViewById(R.id.member_avatar);
                cidText = itemView.findViewById(R.id.member_cid);
                fidText = itemView.findViewById(R.id.member_fid);
            }

            void bind(String fid) {
                avatar.setTag(fid);
                avatar.setImageResource(R.drawable.ic_person);
                avatar.setImageTintList(ColorStateList.valueOf(getColor(R.color.hint)));
                avatar.setPadding(4, 4, 4, 4);
                new Thread(() -> {
                    try {
                        Bitmap bitmap = AvatarManager.getInstance(itemView.getContext()).getAvatarBitmap(fid);
                        if (bitmap != null) {
                            avatar.post(() -> {
                                if (!fid.equals(avatar.getTag())) return;
                                avatar.setImageTintList(null);
                                avatar.setImageBitmap(bitmap);
                                avatar.setPadding(0, 0, 0, 0);
                            });
                        }
                    } catch (Exception ignored) {
                    }
                }).start();

                String cid = cidOf(fid);
                boolean mine = fid.equals(liveFid);
                String primary = cid != null && !cid.isEmpty() ? cid : fid;
                if (mine) primary = getString(R.string.im_mention_you, primary);
                CharSequence label = mine ? primary : ContactLabel.inline(itemView.getContext(), fid, primary);
                if (cid != null && !cid.isEmpty()) {
                    NobodyUi.setName(cidText, fid, label);
                    cidText.setVisibility(View.VISIBLE);
                    fidText.setText(fid);
                    fidText.setVisibility(View.VISIBLE);
                } else {
                    cidText.setVisibility(View.GONE);
                    NobodyUi.setName(fidText, fid, label);
                    fidText.setVisibility(View.VISIBLE);
                }
                itemView.setOnClickListener(v -> pick(fid));
            }
        }
    }
}
