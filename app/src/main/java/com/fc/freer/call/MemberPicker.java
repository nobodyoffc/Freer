package com.fc.freer.call;

import android.content.Context;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.fc.fc_ajdk.data.fcData.Room;
import com.fc.fc_ajdk.data.feipData.Team;
import com.fc.fc_ajdk.utils.StringUtils;
import com.fc.freer.R;
import com.fc.freer.im.ImManager;
import com.fc.freer.manager.FidManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Picking whom a meeting of chosen people invites (Decision 20): the Room's or
 * Team's members, less this identity and anyone already invited.
 */
public final class MemberPicker {

    private MemberPicker() {}

    /** The entity's members, owner included, less {@code except}. */
    public static List<String> members(String entityType, String entityId, Set<String> except) {
        ImManager im = FidManager.getInstance().getImManager();
        Set<String> out = new LinkedHashSet<>();
        if (im != null && "ROOM".equals(entityType)) {
            Room room = im.getRoom(entityId);
            if (room != null) {
                if (room.getOwner() != null) out.add(room.getOwner());
                if (room.getMembers() != null) out.addAll(room.getMembers());
            }
        } else if (im != null && "TEAM".equals(entityType)) {
            Team team = im.getTeam(entityId);
            if (team != null) {
                if (team.getOwner() != null) out.add(team.getOwner());
                if (team.getMembers() != null) for (String f : team.getMembers()) out.add(f);
            }
        }
        out.removeAll(except);
        return new ArrayList<>(out);
    }

    /** A multi-choice list of {@code fids}; {@code chosen} gets at least one of them. */
    public static void show(Context context, List<String> fids, Consumer<List<String>> chosen) {
        if (fids.isEmpty()) {
            Toast.makeText(context, R.string.meeting_no_one_else, Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[fids.size()];
        for (int i = 0; i < names.length; i++) names[i] = name(fids.get(i));
        boolean[] picked = new boolean[fids.size()];
        new AlertDialog.Builder(context)
                .setTitle(R.string.meeting_choose_title)
                .setMultiChoiceItems(names, picked, (d, which, on) -> picked[which] = on)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    List<String> out = new ArrayList<>();
                    for (int i = 0; i < picked.length; i++) if (picked[i]) out.add(fids.get(i));
                    if (out.isEmpty()) Toast.makeText(context, R.string.meeting_nobody_chosen, Toast.LENGTH_LONG).show();
                    else chosen.accept(out);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String name(String fid) {
        ImManager im = FidManager.getInstance().getImManager();
        var p = im == null ? null : im.getTalkPartner(fid);
        String cid = p == null ? null : p.getCid();
        return cid != null && !cid.isEmpty() ? cid : StringUtils.omitMiddle(fid, 20);
    }
}
