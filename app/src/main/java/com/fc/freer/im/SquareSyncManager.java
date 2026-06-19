package com.fc.freer.im;

import static com.fc.fc_ajdk.constants.FieldNames.SQUARE;

import com.fc.fc_ajdk.constants.FieldNames;
import com.fc.fc_ajdk.constants.Values;
import com.fc.fc_ajdk.data.apipData.Fcdsl;
import com.fc.fc_ajdk.data.fcData.Conversation;
import com.fc.fc_ajdk.data.fcData.ImType;
import com.fc.fc_ajdk.data.feipData.Square;
import com.fc.fc_ajdk.db.LocalDB;
import com.fc.fc_ajdk.fapi.client.FapiClient;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.im.dock.DockServiceRegistry;
import com.fc.freer.im.handler.SquareHandler;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.tencent.mmkv.MMKV;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Syncs on-chain squares where liveFid is a member.
 * Uses cursor-based pagination persisted in MMKV.
 */
public class SquareSyncManager {
    private static final String TAG = "SquareSyncManager";
    private static final String MMKV_PREFIX = "square_sync_";
    private static final String CURSOR_KEY = "cursor";
    private static final int PAGE_SIZE = 20;

    private final String liveFid;
    private final MMKV cursorStore;
    private final Gson gson = new Gson();
    private final AtomicBoolean syncing = new AtomicBoolean(false);

    public interface SyncCallback {
        void onSyncComplete(int newCount, int updatedCount);
        void onSyncError(String error);
    }

    public SquareSyncManager(String liveFid) {
        this.liveFid = liveFid;
        this.cursorStore = MMKV.mmkvWithID(MMKV_PREFIX + liveFid, MMKV.SINGLE_PROCESS_MODE);
    }

    /**
     * Run a full incremental sync. Safe to call from any thread.
     * Returns immediately if another sync is already running.
     */
    public void sync(FapiClient fapiClient, SquareHandler squareHandler,
                     LocalDB<Conversation> conversationsDb,
                     DockServiceRegistry dockRegistry, SyncCallback callback) {
        if (!syncing.compareAndSet(false, true)) {
            TimberLogger.d(TAG, "Sync already running, skipping");
            return;
        }

        try {
            int newCount = 0;
            int updatedCount = 0;
            List<String> cursor = loadCursor();

            while (true) {
                Fcdsl fcdsl = new Fcdsl();
                fcdsl.addNewFilter().addNewTerms()
                        .addNewFields(FieldNames.MEMBERS)
                        .addNewValues(liveFid);
                fcdsl.addSort(FieldNames.LAST_HEIGHT, Values.ASC);
                fcdsl.addSort("id", Values.ASC);
                fcdsl.addSize(PAGE_SIZE);

                if (cursor != null && !cursor.isEmpty()) {
                    fcdsl.setAfter(cursor);
                }

                List<Square> squares = fapiClient.entitySearch(SQUARE, fcdsl, Square.class);
                if (squares == null || squares.isEmpty()) break;

                for (Square square : squares) {
                    if (square == null || square.getId() == null) continue;

                    Square existing = squareHandler.getSquare(square.getId());
                    boolean isNew = (existing == null);
                    boolean homeChanged = hasHomeChanged(existing, square);

                    squareHandler.saveSquarePublic(square);

                    String convId = ImType.SQUARE.name() + "_" + square.getId();
                    Conversation conv = conversationsDb.get(convId);

                    boolean liveFidIsMember = square.getMembers() != null
                            && square.getMembers().contains(liveFid);

                    if (conv == null && liveFidIsMember) {
                        conv = new Conversation();
                        conv.setId(convId);
                        conv.setType(ImType.SQUARE);
                        conv.setTargetId(square.getId());
                        conv.setLeftGroup(false);
                        conv.setCreatedAt(square.getBirthTime());
                        updateConvFromSquare(conv, square);
                        conversationsDb.put(convId, conv);
                        registerSquareDock(square, dockRegistry, fapiClient);
                        newCount++;
                    } else if (conv != null) {
                        updateConvFromSquare(conv, square);

                        if (liveFidIsMember) {
                            if (conv.getLeftGroup() != null && conv.getLeftGroup()) {
                                conv.setLeftGroup(false);
                                registerSquareDock(square, dockRegistry, fapiClient);
                                TimberLogger.i(TAG, "Rejoined square: %s", square.getId());
                            } else if (homeChanged) {
                                registerSquareDock(square, dockRegistry, fapiClient);
                                TimberLogger.i(TAG, "Home changed, re-registered dock for square: %s", square.getId());
                            }
                        } else {
                            if (conv.getLeftGroup() == null || !conv.getLeftGroup()) {
                                conv.setLeftGroup(true);
                                if (dockRegistry != null) {
                                    dockRegistry.unregister(SQUARE, square.getId());
                                }
                                TimberLogger.i(TAG, "Left square: %s", square.getId());
                            }
                        }
                        conversationsDb.put(convId, conv);
                        updatedCount++;
                    }
                }

                if (squares.size() < PAGE_SIZE) {
                    saveCursorFromLastSquare(squares);
                    break;
                }
                saveCursorFromLastSquare(squares);
                cursor = loadCursor();
            }

            if (callback != null) callback.onSyncComplete(newCount, updatedCount);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Sync failed: %s", e.getMessage());
            if (callback != null) callback.onSyncError(e.getMessage());
        } finally {
            syncing.set(false);
        }
    }

    private void updateConvFromSquare(Conversation conv, Square square) {
        conv.setDisplayName(square.getName());
        if (square.getNamers() != null && !square.getNamers().isEmpty()) {
            String lastNamer = square.getNamers().get(square.getNamers().size() - 1);
            conv.setAvatarDid(lastNamer);
        }
        conv.setMemberNum(square.getMemberNum());
        conv.settCdd(square.gettCdd());
    }

    private boolean hasHomeChanged(Square existing, Square updated) {
        if (existing == null) return false;
        Map<String, String> oldHome = existing.getHome();
        Map<String, String> newHome = updated.getHome();
        return !Objects.equals(oldHome, newHome);
    }

    private void registerSquareDock(Square square, DockServiceRegistry dockRegistry, FapiClient fapiClient) {
        if (dockRegistry != null && fapiClient != null) {
            dockRegistry.registerSquare(square, fapiClient);
        }
    }

    private void saveCursorFromLastSquare(List<Square> squares) {
        if (squares == null || squares.isEmpty()) return;
        Square last = squares.get(squares.size() - 1);
        if (last.getLastHeight() != null) {
            List<String> cursor = List.of(String.valueOf(last.getLastHeight()), last.getId());
            saveCursor(cursor);
        }
    }

    private List<String> loadCursor() {
        try {
            String json = cursorStore.decodeString(CURSOR_KEY);
            if (json != null) {
                return gson.fromJson(json, new TypeToken<List<String>>(){}.getType());
            }
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to load cursor: %s", e.getMessage());
        }
        return null;
    }

    private void saveCursor(List<String> cursor) {
        try {
            cursorStore.encode(CURSOR_KEY, gson.toJson(cursor));
        } catch (Exception e) {
            TimberLogger.w(TAG, "Failed to save cursor: %s", e.getMessage());
        }
    }
}
