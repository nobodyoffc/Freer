# Prompt: Build a Standalone macOS QR Code App

## Goal

Create an **independent, native macOS application** that replicates and improves upon the feature set of the Android `QrCodeActivity.java` (found at `app/src/main/java/com/fc/freer/qr/QrCodeActivity.java`). The new app must be self-contained — no Android, no Gradle, no JVM runtime dependency on the host.

## Target Platform & Tech Stack

- **OS**: macOS 13 (Ventura) or newer, Apple Silicon + Intel universal binary.
- **Language**: Swift 5.9+
- **UI framework**: SwiftUI (primary) with AppKit bridging only where SwiftUI is insufficient.
- **Camera**: `AVFoundation` (`AVCaptureSession`, `AVCaptureMetadataOutput`) — use the built-in QR metadata detector instead of bundling ZXing.
- **QR generation**: `CoreImage` filter `CIQRCodeGenerator` (or `CIAztecCodeGenerator` / PDF417 if multi-format support is desired).
- **Image decoding (from file)**: `CIDetector(ofType: CIDetectorTypeQRCode, …)` on a `CIImage`.
- **Persistence**: `UserDefaults` for lightweight prefs; `FileManager` for saving generated QR images to `~/Pictures` (or user-chosen location via `NSSavePanel`).
- **Packaging**: Xcode project, signed `.app` bundle, notarized DMG/ZIP for distribution. Provide a `Package.swift` alternative if feasible.

## Feature Parity Requirements

Replicate the behaviors implemented in `QrCodeActivity`:

### 1. Live QR Scanning (camera)
- Request camera permission (`NSCameraUsageDescription` in `Info.plist`).
- Show a live preview inside a rounded scan area.
- Start/stop scanning via a "Scan" button (toggle).
- On successful decode, append the decoded text to the content field and stop scanning automatically.
- Guard against repeated decodes of the same frame while UI updates.
- Handle device permission denial with a clear rationale dialog and a "Open System Settings" shortcut.

### 2. QR Generation
- "Make" button converts the current text field content into a QR code image.
- If the payload is long, **segment** it into multiple QR codes and display them in a paged view (SwiftUI `TabView` with `PageTabViewStyle`), showing `current/total` indicator — mirrors `QRPagerAdapter` behavior.
- Provide a "Save" action that writes all displayed QR bitmaps to disk as PNGs with timestamped filenames: `QR_<epochMillis>_<index>.png`.

### 3. Scan from Image File
- "Gallery" button opens `NSOpenPanel` filtered to image types (`.png`, `.jpg`, `.jpeg`, `.heic`, `.tiff`, `.bmp`).
- Decode the chosen image and append result to the text field; toast on failure.

### 4. Text Field Utilities
- "Clear" button empties the field.
- "Copy" button copies the current field to `NSPasteboard`.
- When running in **return-string mode** (launched by another process via URL scheme or XPC), replace "Copy" with "Return" that sends the value back to the caller, sets a result, and quits.

### 5. Affair JSON Detection
- Preserve the "do affair" side-button: when the text contains `"n":"affair"` and parses as an `Affair` object, show a button that would launch an affair handler.
- For the macOS port, **stub** this out behind a protocol `AffairHandler` with a default no-op implementation, so the port stays self-contained but the extension point is documented.

### 6. Keyboard & Focus
- Clicking the background should resign first responder on the text field.
- Typing in the text field should cancel any active scan (match `stopScanning()` on focus + edit).

### 7. Back / Close Behavior
- If launched in return-string mode with non-empty content, the window-close action must deliver the result before terminating.
- Otherwise, standard window close.

## UX Notes

- Dark-mode first (the Android version forces a black status bar); respect system appearance but default comfortably on dark.
- Use SF Symbols for all icons (camera, photo, doc.on.doc, arrow.uturn.backward, trash, qrcode).
- Localize user-visible strings via `Localizable.strings`; port the existing Android string keys (`menu_qr_code`, `copied_to_clipboard`, `error_creating_qr`, `qr_saved_count`, `cannot_open_image`, `cannot_decode_image`, `retry_after_authorization`, etc.) to matching English keys.
- Show non-blocking toasts via an overlay view (or `NSUserNotification`-style in-app banner).

## Architecture

Split the app into these Swift modules/folders:

```
QrMac/
├─ App/              // @main, AppDelegate, scene setup
├─ Views/            // SwiftUI views: ContentView, ScannerView, QRPagerView, ToastView
├─ ViewModels/       // QrViewModel (ObservableObject) — owns state, scan toggle, text
├─ Services/
│   ├─ CameraScanner.swift     // AVCaptureSession wrapper, Combine publisher for results
│   ├─ QRGenerator.swift        // CIQRCodeGenerator + segmentation logic
│   ├─ QRImageDecoder.swift     // CIDetector-based still-image decode
│   └─ Clipboard.swift
├─ Models/           // AffairStub, QrSegment, SaveResult
└─ Resources/        // Assets.xcassets, Localizable.strings, Info.plist
```

Drive UI with `@StateObject var vm: QrViewModel`. Keep services protocol-based so they are unit-testable without the camera.

## Non-Goals

- Do **not** port the YUV→RGB manual conversion from `imageProxyToBitmap` — `AVCaptureMetadataOutput` returns the decoded string directly; manual pixel munging is unnecessary on macOS.
- Do **not** port ZXing — use Apple's built-in detectors.
- Do **not** depend on any part of the `FC-AJDK` Java/Kotlin library; replace the `Affair` parse with a stub protocol.

## Deliverables

1. Xcode project (`QrMac.xcodeproj`) that builds a universal `.app` out of the box.
2. README with: build steps, code-signing notes, entitlements (`com.apple.security.device.camera`, `com.apple.security.files.user-selected.read-write`), and how to invoke "return-string mode".
3. Unit tests for `QRGenerator` segmentation and `QRImageDecoder` round-trip.
4. A short MIGRATION.md mapping each `QrCodeActivity` method to its Swift equivalent, so reviewers can verify feature parity.

## Reference

Source of truth for behavior: `app/src/main/java/com/fc/freer/qr/QrCodeActivity.java` in the Freer Android repo. When in doubt about edge cases (empty text, permission denied mid-scan, very long payloads), match the Android behavior unless it is clearly an Android-only quirk.
