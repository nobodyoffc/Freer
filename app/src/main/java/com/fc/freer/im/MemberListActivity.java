package com.fc.freer.im;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fc.fc_ajdk.data.fcData.Room;
import com.fc.fc_ajdk.data.fchData.Freer;
import com.fc.fc_ajdk.data.feipData.Square;
import com.fc.fc_ajdk.data.feipData.Service;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.nobody.NobodyUi;
import com.fc.freer.nobody.NobodyRegistry;
import com.fc.freer.BaseCryptoActivity;
import com.fc.freer.FreerApplication;
import com.fc.freer.R;
import com.fc.freer.initiate.SettingManager;
import com.fc.freer.manager.AvatarManager;
import com.fc.freer.manager.CidFidManager;
import com.fc.freer.model.Setting;
import com.fc.freer.ui.DetailActivity;
import com.fc.freer.ui.WaitingDialog;
import com.fc.freer.utils.ApiCenter;
import com.fc.freer.utils.ToastUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MemberListActivity extends BaseCryptoActivity {
    private static final String TAG = "MemberListActivity";
    public static final String EXTRA_GROUP_ID = "extra_group_id";
    public static final String EXTRA_ENTITY_TYPE = "extra_entity_type";

    private static final int PAGE_SIZE = 50;
    private static final int PREFETCH_DISTANCE = 10;

    private RecyclerView membersRecyclerView;
    private ImageButton backButton;
    private String groupId;
    private String entityType;
    private final List<MemberInfo> allMembers = new ArrayList<>();
    private final List<MemberInfo> displayedMembers = new ArrayList<>();
    private MemberAdapter adapter;

    @Override
    protected int getLayoutId() {
        return R.layout.activity_member_list;
    }

    @Override
    protected String getActivityTitle() {
        String type = getIntent().getStringExtra(EXTRA_ENTITY_TYPE);
        if ("team".equals(type)) {
            return getString(R.string.team_members);
        } else if ("room".equals(type)) {
            return getString(R.string.members);
        }
        return getString(R.string.square_members);
    }

    @Override
    protected void initializeViews() {
        membersRecyclerView = findViewById(R.id.members_recycler_view);
        backButton = findViewById(R.id.back_button);

        groupId = getIntent().getStringExtra(EXTRA_GROUP_ID);
        entityType = getIntent().getStringExtra(EXTRA_ENTITY_TYPE);
        if (groupId == null) {
            finish();
            return;
        }

        membersRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        loadMembers();
    }

    @Override
    protected void setupButtons() {
        backButton.setOnClickListener(v -> {
            hideKeyboard();
            finish();
        });
    }

    @Override
    protected void handleQrScanResult(int requestCode, String qrContent) {
    }

    private void loadMembers() {
        Setting setting = SettingManager.getInstance().getCurrentSetting();
        if (setting == null || setting.getImManager() == null) {
            ToastUtils.makeText(this, getString(R.string.im_manager_init_failed));
            return;
        }

        ImManager imManager = setting.getImManager();

        if ("team".equals(entityType)) {
            Team team = imManager.getTeam(groupId);
            if (team != null) {
                String owner = team.getOwner();
                Set<String> managerSet = new HashSet<>();
                if (team.getManagers() != null) {
                    managerSet.addAll(team.getManagers());
                }

                // Members the chain still lists as owing a signature on the current consensus.
                Set<String> notAgreedSet = new HashSet<>();
                if (team.getNotAgreeMembers() != null) {
                    notAgreedSet.addAll(team.getNotAgreeMembers());
                }

                if (team.getMembers() != null) {
                    for (String member : team.getMembers()) {
                        String role;
                        if (member.equals(owner)) {
                            role = getString(R.string.role_owner);
                        } else if (managerSet.contains(member)) {
                            role = getString(R.string.role_manager);
                        } else {
                            role = getString(R.string.role_member);
                        }
                        if (notAgreedSet.contains(member)) {
                            role = role + " · " + getString(R.string.role_not_agreed);
                        }
                        allMembers.add(new MemberInfo(member, role));
                    }
                }

                if (team.getInvitees() != null) {
                    for (String invitee : team.getInvitees()) {
                        allMembers.add(new MemberInfo(invitee, getString(R.string.role_invited)));
                    }
                }
            }
        } else if ("room".equals(entityType)) {
            Room room = imManager.getRoom(groupId);
            if (room != null && room.getMembers() != null) {
                String owner = room.getOwner();
                for (String member : room.getMembers()) {
                    String role = member.equals(owner) ? getString(R.string.role_owner) : null;
                    allMembers.add(new MemberInfo(member, role));
                }
            }
        } else {
            Square square = imManager.getSquare(groupId);
            if (square != null && square.getMembers() != null) {
                Set<String> namerSet = new HashSet<>();
                if (square.getNamers() != null) {
                    namerSet.addAll(square.getNamers());
                }
                String namerRole = getString(R.string.role_namer);
                for (String member : square.getMembers()) {
                    String role = namerSet.contains(member) ? namerRole : null;
                    allMembers.add(new MemberInfo(member, role));
                }
            }
        }

        adapter = new MemberAdapter(displayedMembers);
        membersRecyclerView.setAdapter(adapter);
        appendPage();

        List<String> memberFids = new java.util.ArrayList<>();
        for (MemberInfo info : allMembers) memberFids.add(info.fid);
        bindConsensusNote(memberFids);
        NobodyUi.observe(this, fids -> {
            adapter.notifyDataSetChanged();
            bindConsensusNote(memberFids);
        });
        NobodyUi.resolveAsync(memberFids);

        membersRecyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy <= 0) return;
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm == null) return;
                int lastVisible = lm.findLastVisibleItemPosition();
                if (lastVisible >= displayedMembers.size() - PREFETCH_DISTANCE
                        && displayedMembers.size() < allMembers.size()) {
                    appendPage();
                }
            }
        });
    }

    /**
     * Label-only: a nobody member's consent can be given by anyone. Counting is
     * left to the protocol; this only says so.
     */
    private void bindConsensusNote(List<String> memberFids) {
        TextView note = findViewById(R.id.nobodyBanner);
        if (note == null) return;
        boolean anyNobody = "team".equals(entityType)
                && !NobodyRegistry.get().nobodiesOf(memberFids).isEmpty();
        if (anyNobody) note.setText(R.string.nobody_member_consensus_note);
        note.setVisibility(anyNobody ? View.VISIBLE : View.GONE);
    }

    private void appendPage() {
        int start = displayedMembers.size();
        int end = Math.min(start + PAGE_SIZE, allMembers.size());
        if (end <= start) return;
        displayedMembers.addAll(allMembers.subList(start, end));
        if (adapter != null) {
            adapter.notifyItemRangeInserted(start, end - start);
        }
    }

    private void showFreerDetail(String fid) {
        WaitingDialog waitingDialog = new WaitingDialog(this, getString(R.string.loading_fid_info));
        waitingDialog.show();

        new Thread(() -> {
            try {
                ApiCenter apiCenter = ApiCenter.getInstance();
                if (apiCenter == null) {
                    runOnUiThread(() -> {
                        waitingDialog.dismiss();
                        ToastUtils.makeText(this, getString(R.string.toast_api_center_unavailable));
                    });
                    return;
                }

                FapiClient fapiClient = (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
                if (fapiClient == null) {
                    runOnUiThread(() -> {
                        waitingDialog.dismiss();
                        ToastUtils.makeText(this, getString(R.string.toast_fapi_client_unavailable));
                    });
                    return;
                }

                Freer freerInfo = fapiClient.getFreer(fid);

                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    if (freerInfo != null) {
                        Intent intent = new Intent(this, DetailActivity.class);
                        intent.putExtra(DetailActivity.EXTRA_ENTITY_JSON, freerInfo.toJson());
                        intent.putExtra(DetailActivity.EXTRA_ENTITY_CLASS, Freer.class.getName());
                        startActivity(intent);
                    } else {
                        ToastUtils.makeText(this, getString(R.string.failed_to_load_fid_info));
                    }
                });
            } catch (Exception e) {
                TimberLogger.e(TAG, "Error fetching Freer info: %s", e.getMessage());
                runOnUiThread(() -> {
                    waitingDialog.dismiss();
                    ToastUtils.makeText(this, getString(R.string.failed_to_load_fid_info));
                });
            }
        }).start();
    }

    static class MemberInfo {
        final String fid;
        final String role;

        MemberInfo(String fid, String role) {
            this.fid = fid;
            this.role = role;
        }
    }

    private static final Set<String> noCidFids = Collections.synchronizedSet(new HashSet<>());

    private static String resolveCid(String fid) {
        CidFidManager cidFidManager = CidFidManager.getInstance();
        if (cidFidManager != null) {
            String cid = cidFidManager.getCidByFid(fid);
            if (cid != null && !cid.isEmpty()) {
                return cid;
            }
        }

        if (noCidFids.contains(fid)) return null;

        try {
            ApiCenter apiCenter = ApiCenter.getInstance();
            if (apiCenter == null) return null;

            FapiClient fapiClient = (FapiClient) apiCenter.getClient(Service.ServiceType.FAPI_No1_NrC7);
            if (fapiClient == null) return null;

            Freer freer = fapiClient.getFreer(fid);
            if (freer != null && freer.getCid() != null && !freer.getCid().isEmpty()) {
                if (cidFidManager != null) {
                    cidFidManager.add(fid, freer.getCid());
                }
                return freer.getCid();
            } else {
                noCidFids.add(fid);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private class MemberAdapter extends RecyclerView.Adapter<MemberAdapter.MemberViewHolder> {
        private final List<MemberInfo> memberList;

        MemberAdapter(List<MemberInfo> memberList) {
            this.memberList = memberList;
        }

        @NonNull
        @Override
        public MemberViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_member, parent, false);
            return new MemberViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull MemberViewHolder holder, int position) {
            MemberInfo info = memberList.get(position);
            holder.bind(info);
        }

        @Override
        public int getItemCount() {
            return memberList.size();
        }

        class MemberViewHolder extends RecyclerView.ViewHolder {
            private final ImageView avatar;
            private final TextView cidText;
            private final TextView fidText;

            MemberViewHolder(@NonNull View itemView) {
                super(itemView);
                avatar = itemView.findViewById(R.id.member_avatar);
                cidText = itemView.findViewById(R.id.member_cid);
                fidText = itemView.findViewById(R.id.member_fid);
            }

            void bind(MemberInfo info) {
                String fid = info.fid;
                avatar.setTag(fid);
                cidText.setTag(fid);
                fidText.setTag(fid);

                avatar.setImageResource(R.drawable.ic_person);
                avatar.setImageTintList(ColorStateList.valueOf(
                        itemView.getContext().getResources().getColor(R.color.hint, null)));
                avatar.setPadding(4, 4, 4, 4);

                CidFidManager cidFidManager = CidFidManager.getInstance();
                String cachedCid = cidFidManager != null ? cidFidManager.getCidByFid(fid) : null;
                renderIdentifier(fid, cachedCid, info.role);

                new Thread(() -> {
                    try {
                        AvatarManager avatarManager = AvatarManager.getInstance(itemView.getContext());
                        Bitmap bitmap = avatarManager.getAvatarBitmap(fid);
                        if (bitmap != null) {
                            avatar.post(() -> {
                                if (fid.equals(avatar.getTag())) {
                                    avatar.setImageTintList(null);
                                    avatar.setImageBitmap(bitmap);
                                    avatar.setPadding(0, 0, 0, 0);
                                }
                            });
                        }
                    } catch (Exception e) {
                        TimberLogger.e(TAG, "Failed to load avatar for FID %s: %s", fid, e.getMessage());
                    }

                    if (cachedCid == null || cachedCid.isEmpty()) {
                        String resolved = resolveCid(fid);
                        if (resolved != null && !resolved.isEmpty()) {
                            cidText.post(() -> {
                                if (fid.equals(cidText.getTag())) {
                                    renderIdentifier(fid, resolved, info.role);
                                }
                            });
                        }
                    }
                }).start();

                avatar.setOnClickListener(v -> {
                    hideKeyboard();
                    showFreerDetail(fid);
                });

                View.OnClickListener copyFid = v -> {
                    hideKeyboard();
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("FID", fid);
                    clipboard.setPrimaryClip(clip);
                    ToastUtils.makeText(MemberListActivity.this, getString(R.string.fid_copied_to_clipboard));
                };
                View.OnLongClickListener addFid = v -> {
                    hideKeyboard();
                    FreerApplication.addFid(fid);
                    ToastUtils.makeText(MemberListActivity.this, getString(R.string.fid_added_to_list));
                    return true;
                };
                fidText.setOnClickListener(copyFid);
                fidText.setOnLongClickListener(addFid);
                cidText.setOnClickListener(copyFid);
                cidText.setOnLongClickListener(addFid);
            }

            private void renderIdentifier(String fid, String cid, String role) {
                boolean hasCid = cid != null && !cid.isEmpty();
                String primary = hasCid ? cid : fid;
                String display = (role != null && !role.isEmpty())
                        ? primary + " [" + role + "]"
                        : primary;
                if (hasCid) {
                    NobodyUi.setName(cidText, fid, display);
                    cidText.setVisibility(View.VISIBLE);
                    fidText.setVisibility(View.GONE);
                } else {
                    cidText.setVisibility(View.GONE);
                    NobodyUi.setName(fidText, fid, display);
                    fidText.setVisibility(View.VISIBLE);
                }
            }
        }
    }
}
