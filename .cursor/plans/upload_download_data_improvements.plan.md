---
name: Upload Download Data Improvements
overview: Improve UploadDataActivity and DownloadDataActivity with encrypted-by-default upload flow, multi-location download (fudp:// and (sid) support), batch DID pre-check, per-HAT progress bars, background UploadService/DownloadService, and a completion summary dialog with total fee.
todos: []
isProject: false
---

# Upload/Download Data Improvements Plan

## Requirements Analysis

### Reasonability and Feasibility


| Requirement                         | Status                    | Notes                                                                                                                                                                                                       |
| ----------------------------------- | ------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1. Upload cipher by default         | **Largely exists**        | [DataSyncManager](app/src/main/java/com/fc/freer/data/DataSyncManager.java) already implements the two-HAT cipher model. Steps align with 1–6; cipherDid written to raw HAT via `addCipherId` after upload. |
| 2. Download from multiple locations | **Partial + new work**    | Migrate from `disk://` to `fudp://` and `(sid)`; add `FapiClient.bootstrapFromUrl` and `bootstrapFromSid` in FC-AJDK and FC-JDK.                                                                            |
| 3. Batch DID check before upload    | **Feasible**              | `FapiClient.diskCheck(List<String> dids)` exists (max 200/request).                                                                                                                                         |
| 4. Progress bars in HAT cards       | **Infrastructure exists** | [HatCardContainer](app/src/main/java/com/fc/freer/utils/HatCardContainer.java) has `showProgress`/`updateProgress`/`hideProgress`. `diskCarve` and `diskGet` support `LongConsumer progressCallback`.       |
| 5. Background Service               | **Feasible**              | New Android `Service` (foreground for Android 8+). Implement from scratch.                                                                                                                                  |
| 6. Completion summary dialog        | **Feasible**              | Create `SyncResultDialog` with total fee, counts, etc.                                                                                                                                                      |


---

## Clarifications Incorporated

### 1. Loca formats (3 only)

- **a. `local://path**` – Local file path
- **b. `fudp://host:port**` – Replaces `disk://did`. Server endpoint; DID = HAT id when the HAT references disk-stored data (cipher HAT id = cipher DID, raw HAT id = raw DID)
- **c. `(sid)<serviceId>**` – Service ID; resolve to URL via `entityByIds(SERVICE, Service.class, [sid])`, then use same logic as (b)

### 2. FapiClient bootstrap APIs

Add to both:

- **FC-AJDK**: `FC-AJDK/src/main/java/com/fc/fc_ajdk/fapi/client/FapiClient.java`
- **FC-JDK**: `/Users/liuchangyong/Desktop/Freeverse/FC-JDK/src/main/java/fapi/client/FapiClient.java`

**New static methods:**

- `bootstrapFromUrl(FudpNode fudpNode, String url, ...)` – Parse `fudp://host:port`, call `discoverViaHelloAndPing`, filter for DISK/FAPI service, return `FapiClient`
- `bootstrapFromSid(FudpNode fudpNode, String sid, FapiClient defaultClient, ...)` – Use `defaultClient.entityByIds(IndicesNames.SERVICE, Service.class, [sid])` to get `Service`, extract `getApiUrl()`, then bootstrap from that URL

**Logic changes:**

- Remove all `disk://` usage from DataSyncManager and related code
- Use the default FapiClient only when the SID or URL matches the default DISK server

### 3. Fee display

- Sum `FapiResponse.getCharged()` across all upload/download operations
- Show total fee in the completion summary dialog

---

## Implementation Plan

### Phase 1: Loca format migration and FapiClient APIs

**1.1 FC-AJDK: Add `bootstrapFromUrl` and `bootstrapFromSid**`

- Add `bootstrapFromUrl(FudpNode fudpNode, String url, Map<String,Object> settingMap, ...)`:
  - Parse `fudp://host:port` → host, port
  - Call existing `discoverViaHelloAndPing(fudpNode, host, port, ...)`
  - From `DiscoveryResult`, pick a DISK-capable FAPI service (or first FAPI service)
  - Return `new FapiClient(fudpNode, result.getPeerId(), service.getId(), ...)`
- Add `bootstrapFromSid(FudpNode fudpNode, String sid, FapiClient defaultClient, ...)`:
  - `defaultClient.entityByIds(IndicesNames.SERVICE, Service.class, [sid])`
  - Get `Service`, call `getApiUrl()` (or equivalent from urls map)
  - Call `bootstrapFromUrl` with that URL

**1.2 FC-JDK: Same APIs**

- Mirror the FC-AJDK implementation in FC-JDK `FapiClient`
- Use FC-JDK types (`Settings`, `DiscoveryResult`, etc.)

**1.3 DataSyncManager: Migrate from `disk://` to new loca formats**

- Replace `DISK_LOCATION_PREFIX = "disk://"` with new format constants
- **Upload**: After `diskCarve`, store loca as `fudp://host:port` (from default client's endpoint). Obtain default client's URL from ApiCenter/Setting.
- **Download**: Iterate `locas` in order; for each loca resolve FapiClient (default vs `bootstrapFromUrl` vs `bootstrapFromSid`); call `diskGet(did, outputFile)` with HAT id as DID
- Remove `disk://` parsing from `findDiskLocation`, `checkDataOnDisk`, etc.

**1.4 App-wide loca format updates**

- [DataActivity](app/src/main/java/com/fc/freer/data/DataActivity.java): Change `disk://` checks to `fudp://` and `(sid)` for "has disk"
- [DownloadDataActivity](app/src/main/java/com/fc/freer/data/DownloadDataActivity.java): Same
- [HatCardContainer](app/src/main/java/com/fc/freer/utils/HatCardContainer.java): Update storage icon logic for `fudp://` and `(sid)`
- [HatDetailActivity](app/src/main/java/com/fc/freer/data/HatDetailActivity.java): Update `disk://` references

**Loca format convention:** For `fudp://host:port` and `(sid)serviceId`, the DID used in `diskGet`/`diskCheck` is the HAT's id.

---

### Phase 2: Core upload/download logic

**2.1 Upload flow – ensure cipher-by-default**

- DataSyncManager.uploadData already implements this. Ensure UploadDataActivity.performUpload always calls uploadData (encrypted path), never uploadRawData.

**2.2 Batch DID check before upload**

- Add `DataSyncManager.batchCheckOnDisk(List<Hat> hats): Map<String, DiskItem>`:
  - Collect DIDs from HAT ids (for cipher HATs) and locas that point to disk
  - Batch `diskCheck(dids)` in chunks of 200
  - Return DID → DiskItem map for DIDs that exist
- In UploadDataActivity.performUpload, call this first and skip HATs already on disk

**2.3 Multi-location download**

- In DataSyncManager.downloadData: iterate `locas` in order; for each loca resolve FapiClient; call `diskGet(did, outputFile)`; if no locas work and `cipherIds` exist, fall back to cipher HAT download

**2.4 Progress bars in HAT cards**

- Add overloads in DataSyncManager: `uploadData(..., LongConsumer progressCallback)`, `downloadData(..., LongConsumer progressCallback)`
- Pass callback to `diskCarve` and `diskGet`
- In activities: pass callback that maps bytes to 0–100 and uses `runOnUiThread` to call `hatCardContainer.updateProgress(hatId, progress)`; show/hide progress at start/finish

---

### Phase 3: Completion summary and fee

**3.1 SyncResultDialog**

- New dialog with: total files, uploaded/downloaded count, skipped, failed, **total fee**
- Add strings in `strings.xml`

**3.2 Fee tracking**

- After each `diskCarve`/`diskGet`, read `fapiClient.getLastCharged()`; accumulate in Activity/Service; pass total to SyncResultDialog

---

### Phase 4: Background services

**4.1 UploadService**

- Create `UploadService extends Service`; use `startForeground` (Android 8+); accept hat IDs via Intent; process sequentially; notify progress/completion via broadcast; support cancellation

**4.2 DownloadService**

- Mirror UploadService structure

**4.3 Activity integration**

- Wire Activities to start/stop services; handle progress via broadcasts; option to "Upload/Download in background"

---

### Phase 5: Minor adjustments

- **cipherDid vs cipherIds**: Use `cipherIds` throughout; no schema change
- **Temp cipher file**: DataSyncManager already creates and deletes it in uploadData
- **Raw HAT update**: Cipher referenced via `cipherIds`, not locas

---

## File changes summary


| File                                                                                                                                                                                                                                   | Changes                                                                           |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------- |
| FC-AJDK FapiClient                                                                                                                                                                                                                     | Add `bootstrapFromUrl`, `bootstrapFromSid`                                        |
| FC-JDK FapiClient                                                                                                                                                                                                                      | Same APIs                                                                         |
| [DataSyncManager.java](app/src/main/java/com/fc/freer/data/DataSyncManager.java)                                                                                                                                                       | New loca formats, remove `disk://`, batch check, progress callbacks, fee tracking |
| [UploadDataActivity.java](app/src/main/java/com/fc/freer/data/UploadDataActivity.java)                                                                                                                                                 | Batch check, progress, SyncResultDialog, fee, optionally start UploadService      |
| [DownloadDataActivity.java](app/src/main/java/com/fc/freer/data/DownloadDataActivity.java)                                                                                                                                             | Progress, SyncResultDialog, fee, optionally start DownloadService                 |
| [DataActivity](app/src/main/java/com/fc/freer/data/DataActivity.java), [HatCardContainer](app/src/main/java/com/fc/freer/utils/HatCardContainer.java), [HatDetailActivity](app/src/main/java/com/fc/freer/data/HatDetailActivity.java) | `disk://` → `fudp://` / `(sid)`                                                   |
| **New** UploadService.java                                                                                                                                                                                                             | Foreground service for background upload                                          |
| **New** DownloadService.java                                                                                                                                                                                                           | Foreground service for background download                                        |
| **New** SyncResultDialog.java                                                                                                                                                                                                          | Completion summary with counts and fee                                            |
| **New** dialog_sync_result.xml                                                                                                                                                                                                         | Layout for SyncResultDialog                                                       |
| [AndroidManifest.xml](app/src/main/AndroidManifest.xml)                                                                                                                                                                                | Register UploadService, DownloadService                                           |
| [strings.xml](app/src/main/res/values/strings.xml)                                                                                                                                                                                     | Labels for sync result, progress, fee                                             |


---

## Default DISK server matching

To decide "use default client" for a loca:

- **URL**: Compare `fudp://host:port` with the default FapiClient's endpoint URL
- **SID**: Compare `(sid)serviceId` with the default FapiClient's `serviceSid`

Store default endpoint URL and service SID in Setting/ApiCenter for comparison.

---

## Dependency considerations

- **Android 8+**: `startForeground` must be called quickly after `onStartCommand` to avoid ANR
- **HatCardContainer**: Progress updates must run on main thread; use `runOnUiThread` or `Handler(Looper.getMainLooper())`

---

## Suggested implementation order

1. Phase 1: Loca migration and FapiClient APIs (FC-AJDK, FC-JDK, DataSyncManager, app-wide)
2. Phase 2.2: Batch DID check
3. Phase 2.4: Progress bars
4. Phase 3: SyncResultDialog and fee tracking
5. Phase 4: UploadService and DownloadService

