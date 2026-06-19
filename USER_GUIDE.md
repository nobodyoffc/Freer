# Freer User Guide

---

# Glossary

- **FID**: Freecash Identity, your unique identity on the Freecash blockchain
- **CID**: The human-readable identity name of FID. It is carved on-chain by the FID itself.
- **Multisig**: Multi-signature, requiring multiple private keys to authorize a transaction
- **Master FID**: The primary identity that controls servant FIDs
- **Servant FID**: A subordinate identity managed by a master FID
- **Carve**: Permanently inscribe data onto the blockchain
- **TOTP**: Time-based One-Time Password, used for two-factor authentication
- **DOCK**: Decentralized On-Chain Knowledge, a service endpoint for receiving offline P2P messages
- **FCH**: Freecash coin unit (1 FCH = 100,000,000 satoshis)
- **Cash**: Unspent transaction outputs (UTXOs) available for spending
- **Symkey**: Symmetric encryption key used for group messaging

---

# Part I: Basic

## 1. Getting Started

### 1.1 Installation

Freer requires Android 9.0 (API 28) or higher. Install the APK on your Android device to get started.

### 1.2 Create Password

On first launch, the app prompts you to create a password:

1. Enter a password (minimum 4 characters).
2. Confirm the password by entering it again.
3. The password protects all your keys and data within the app.

You can also scan a password via QR code for convenience. The password is used to derive encryption keys for local storage — it cannot be recovered if forgotten.

### 1.3 Create or Import Identity (FID)

After setting your password, you will be asked to create or import an FID. Several methods are available:

- **By Mnemonic Phrase**: Enter a secret phrase. The app generates a private key from it. A phrase should be at least 60 characters with 6+ random characters for security.
- **By Private Key**: Enter a raw private key in WIF, hex, or other supported format. You can also scan a QR code.
- **By Public Key**: Import a watch-only FID using only a public key. You can monitor the address but cannot sign transactions.
- **From File/JSON**: Load an encrypted or plaintext KeyInfo JSON file from your device.
- **Random Generation**: Generate a new random private key instantly.

After import, you can optionally add a label (display name) for easy identification.

---

## 2. Identity Management

### 2.1 Understanding FID/CID

- **FID** is your unique blockchain address, derived from your public key.
- **CID** is a human-readable identity name you carve on-chain for your FID.
- The app displays your CID if available; otherwise it shows the FID.

### 2.2 Switch Between Identities

You can manage multiple FIDs under one password. On the identity selection screen, all imported FIDs appear as cards showing avatar, name, balance, and cash count. Tap a card to set it as your active ("Live") FID — all subsequent operations use this identity.

### 2.3 Add Keys (Private Key, Public Key, Mnemonic Phrase)

You can import additional identities at any time through the key management screen. Supported input methods include:

- Scan QR code
- Paste from clipboard
- Manual text entry
- Load from file

Supported key formats: WIF, hex, Base58Check, compressed public key (33-byte), and encrypted prikey cipher (JSON).

### 2.4 Master and Servant FIDs

- **Master FID**: A higher-level FID that can recover your private key if your device is lost. Setting a master carves your encrypted private key on-chain. Choose a trusted master carefully.
- **Servant FID**: FIDs that serve under your FID. You can view your servants and decrypt their private keys if needed for recovery.

To set a master: search for the master's FID or public key, then confirm. The encrypted key is carved on-chain.

### 2.5 Backup Private Key

From the backup dialog, you can view and export your private key in multiple ways:

- **Encrypted cipher** (default, most secure) — displayed as text or QR code
- **Plain private key** in hex or Base58Check — with visibility toggle
- **Copy to clipboard** or **display as QR code** for printing or physical storage

Always back up your private key in at least 2 different locations using at least 2 different methods.

---

## 3. Wallet & Payments

### 3.1 View Balance

Your balance is displayed on the Live FID card on the home screen, showing:

- Balance in FCH
- Number of cash (unspent outputs)
- CD value
- A lock icon if the FID is watch-only (no private key)

### 3.2 Receive Payments (Including Income History)

The Receive screen displays your FID and a QR code for others to scan. You can:

- Generate a QR code with just your FID
- Include a specific amount in the QR code
- Copy your FID to clipboard
- Switch to the income view to see all received transactions with sender, amount, and date

### 3.3 Send Payments (Including Expense History)

To send a payment:

1. Select or enter the recipient's FID (from contacts, QR scan, or manual input).
2. Specify the amount.
3. Review the transaction summary.
4. Sign and broadcast.

You can view your expense history to track all sent transactions.

### 3.4 Carve

Carve permanently inscribes text onto the blockchain. On the Carve screen:

1. Enter the text you want to store on-chain.
2. Optionally add payees to send coins alongside the carve.
3. Review the fee and remaining balance.
4. Sign and broadcast the transaction.

Carved data is permanent and cannot be modified or deleted.

### 3.5 Manage Cashes

The Cash screen lets you view and manage your unspent transaction outputs (UTXOs):

- Sort by amount or CD value (ascending/descending)
- Select multiple items for batch operations
- View total selected amount and count
- Send, consolidate, or reorganize selected cash
- Import cash from external sources
- Pull to refresh for latest data

### 3.6 Manage TX (Import TX, Export TX, TX History)

Transaction management features:

- **TX History**: Browse all past transactions with details
- **Import TX**: Load raw transaction hex from file, clipboard, or QR code
- **Export TX**: Copy transaction data to clipboard or generate a QR code for transfer between devices

### 3.7 Off-line TX (Make Raw TX and Broadcast TX)

Freer supports an offline signing workflow with the companion app **Safe**:

1. **On your online device (Freer)**: Create the transaction and define inputs and outputs. Copy the raw unsigned transaction hex or display it as QR code.
2. **On your offline device (Safe)**: Import the raw transaction, sign it with your private key kept offline.
3. **Back on your online device (Freer)**: Import the signed transaction and broadcast it to the network.

This workflow ensures your private key never touches an internet-connected device.

---

# Part II: Personal

## 4. Secrets

### 4.1 Create Encrypted Secrets

Secrets are encrypted personal data stored locally or on-chain. To create a secret:

1. Navigate to "My Secrets" and tap "Create Secret."
2. Enter a title, content, and optional memo or classification.
3. Content is encrypted using your Live FID's private key.
4. Choose to store locally only (off-chain) or carve to blockchain (on-chain).

Secrets support categorization by type (e.g., Password, TOTP, Note).

### 4.2 Backup & Restore

**Backup:**
- Select "Backup off-chain secrets" from the menu.
- Secrets are exported in JSON format, processed in batches (default 50 per batch).
- The backup file is saved to the Downloads folder with a timestamp.
- You can also copy to clipboard or generate QR codes.

**Restore:**
- Use the Import function to restore from backup.
- Paste JSON, load from file, scan QR code, or upload.
- The system detects duplicates and prompts you to replace or keep existing entries.

### 4.3 Export / Import

- **Export**: Generates a JSON file containing a backup header (FID, timestamp, item count) and encrypted secret objects.
- **Import**: Accepts JSON from multiple sources. Supports password-protected imports. Validates required fields and proper encryption before importing.

### 4.4 Import TOTP Codes

Import two-factor authentication codes from other apps:

- **Standard JSON** format with "secret" and "label" fields
- **OTPAuth URI** format (`otpauth://totp/...`)
- **Encrypted FEIP** secret format with password protection

Input via file import, QR scan, or clipboard paste. Each TOTP entry is stored as a Secret with type "TOTP."

---

## 5. Data / Disk

### 5.1 Upload

Upload locally stored files to the remote FAPI.DISK service:

- Select local-only items from the Data screen.
- Tap "Upload" to start the background upload service.
- Files are encrypted before upload using your FID's encryption key.
- A progress bar shows upload status.

### 5.2 Download

Download files from FAPI.DISK to your local device:

- Select remote-only items from the Data screen.
- Tap "Download" to fetch and decrypt files.
- Downloaded files are cached locally for offline access.

### 5.3 Backup

Create a full snapshot of all your data records and metadata:

- Can be triggered manually or automatically via a background worker.
- Backup file is saved locally in JSON format with a timestamp.
- Use this to recover if local data is lost.

### 5.4 Sync

Bidirectional synchronization between local storage and FAPI.DISK:

- **Local-only items** can be uploaded to DISK.
- **DISK-only items** can be downloaded locally.
- **Already synced items** are shown as read-only.
- Pull to refresh triggers a sync check.

The Data screen shows all items sorted by last access time, with name, size, and creation/update dates. You can search, sort, and batch-select items.

---

# Part III: Social

## 6. Contacts

### 6.1 Manage On-chain Contacts

Contacts are stored on the blockchain as FEIP Contact records. Each contact includes an FID (required), name, avatar, and optional memo.

To add a contact:
1. Navigate to "My Contacts" and tap "Create Contact."
2. Enter the contact's FID and name.
3. The contact is carved on-chain and becomes permanently recorded.

The system detects duplicates and prompts you before replacing an existing contact.

### 6.2 FID List

The FID List is a curated list of frequently-used FIDs for quick access:

- Add FIDs individually or as a batch (space or comma separated).
- Use the FID List for quick selection when composing mail or starting chats.
- Add FIDs directly from contact details.
- Clear the entire list in one action if needed.

### 6.3 Blacklist

Block specific FIDs from contacting you:

- Add FIDs to the blacklist from message request dialogs or the blacklist manager.
- View all blocked FIDs in the Blacklist screen.
- Remove entries with a confirmation dialog.
- Blacklisted FIDs cannot send you P2P messages.

---

## 7. Mail (On-chain Encrypted Message)

### 7.1 Send Mail

1. Open "Create Mail."
2. Select a recipient from your contacts or enter an FID manually.
3. Compose your message.
4. Optionally set a custom notice fee.
5. Tap "Carve" to encrypt the content with the recipient's public key and send it to the blockchain.

### 7.2 Read Mail

The mail list shows all received messages with sender, time, and a content preview. Tap a mail to view the full decrypted content, sender information, timestamp, notice fee, and encryption status.

### 7.3 Reply to Mail

From the mail detail view, tap "Reply" to compose a response. The recipient is automatically filled with the original sender's FID. Each reply is a separate on-chain transaction (not threaded).

### 7.4 Delete Mail

- **Local delete**: Removes mail from your local database only.
- **On-chain delete**: Carves a deletion record to the blockchain, making the mail invisible on-chain while preserving the record.
- Deleted mails can be recovered from the "Deleted Mails" section.

### 7.5 Save Draft

Compose your mail content before sending. Note that drafts are not auto-saved — if you leave the compose screen without carving, the content will be lost.

### 7.6 Set Notice Fee

Configure fees for mail delivery:

- **My Notice Fee**: The fee others must pay to send you mail. Set it by entering an amount and carving on-chain.
- **Max Paying Fee**: The maximum fee you are willing to pay when sending mail to others. Saved locally.
- **Pay Back**: Enable this to automatically refund the notice fee when you reply to a mail.

Maximum paying notice fee is 21,000 satoshis.

---

## 8. P2P Talk

### 8.1 Start a New Chat (Set DOCK On-chain First)

Before you can receive P2P messages, you must register a DOCK service endpoint on-chain:

1. The app prompts you to set up DOCK if not configured.
2. Enter or confirm the DOCK URL.
3. The DOCK is carved on-chain as a HOME service record.

To start a new chat, search for an FID, choose from your contacts, or enter an FID directly.

### 8.2 Send Messages (Text, Voice, File)

- **Text**: Type and send text messages in real-time.
- **Voice**: Hold the record button to capture a voice message (microphone permission required). Slide to cancel.
- **File**: Share files and documents via the integrated file picker.
- **Emoji**: Full emoji keyboard with category tabs.

### 8.3 Message Requests from Strangers

When a stranger (non-contact) messages you for the first time:

1. The message is quarantined as a pending request.
2. A "Message Requests" banner appears with a count badge.
3. Open the requests to preview the stranger's message.
4. Choose to **Accept** (promotes to normal conversation), **Reject** (purges the message), or **Blacklist** the sender.
5. A 30-second countdown timer auto-dismisses the dialog if no action is taken.

### 8.4 Conversation Management

The Talk screen shows all P2P conversations:

- Search by partner's FID or name.
- Pull to refresh for new messages.
- Select multiple conversations for batch deletion.
- Unread message count displayed per conversation.
- Scroll down to load older conversations.

### 8.5 Stranger Policy & Blacklist

Configure how the app handles messages from unknown senders in P2P Chat Settings:

- **Reject All Strangers**: Block all messages from non-whitelisted FIDs.
- **Auto-Accept Contacts**: Automatically accept messages from saved contacts only.
- **Manage Blacklist**: View, add, or remove blocked FIDs.

Policy modes: Accept All (except blacklist), Whitelist Only, Contacts Only, or Accept None (do not disturb).

---

## 9. Group Chat

### 9.1 Room

Rooms are **private, off-chain** group chats:

- **Create**: Set a name, optional description, and add members. Messages are encrypted with a symmetric key.
- **Invite**: Add members who receive the room's symmetric key for decryption.
- **Messaging**: All messages are encrypted and delivered off-chain through DOCK. Only members with the symkey can read messages.
- **Manage**: Update room info, view members, kick members (owner only).
- **Leave/Close**: Members can leave; only the owner can close the room (all members lose access permanently).

If the symkey is lost, request it from another member.

### 9.2 Square

Squares are **public, on-chain** broadcast channels:

- **Create**: Set a name and description. The square is carved on-chain.
- **Join/Quit**: Anyone can join a square. Quit history is preserved.
- **Messaging**: All messages are public, unencrypted, and stored on-chain. Visible to everyone.
- **Moderation**: The owner can moderate and remove messages.

### 9.3 Team

Teams are **encrypted, on-chain** groups with managed membership:

- **Create**: Set a name, optional consensus ID, and description. Carved on-chain.
- **Invite**: Send on-chain invitations to FIDs. Recipients can accept or reject.
- **Messaging**: Messages are encrypted with a team symmetric key and stored on-chain. Only members can decrypt.
- **Manage**: View members, kick members (owner only), transfer ownership, update team info.
- **Leave/Disband**: Members can leave; only the owner can disband the team (irreversible).
- **History Sharing**: Request or share message history with specific members.

| Feature | Room | Square | Team |
|---------|------|--------|------|
| **Members** | Off-chain (private) | On-chain (public) | On-chain (managed) |
| **Messages** | Encrypted, off-chain | Public, on-chain | Encrypted, on-chain |
| **Joining** | Invite only | Open to all | Approval required |
| **Visibility** | Private | Public | Members only |

---

# Part IV: Financial

## 10. Proofs

Proofs are on-chain attestations or certificates you can issue to establish verifiable claims.

### 10.1 Create Proof

Compose a proof with a title and content. You can:

- Add co-signers (other FID holders) to validate the proof.
- Set whether the proof is transferable.
- Specify if all co-signers must approve or only some.
- Carve the proof on-chain to make it permanently verifiable.

### 10.2 Update Proof

Modify an existing proof's title, content, or co-signer list before or after issuing.

### 10.3 Delete Proof

Destroy a proof on-chain. Destroyed proofs are moved to a "destroyed" section and can be recovered for a limited time.

### 10.4 View Proofs

Browse all proofs you have issued or received. Sort by date, owner, or name. View proof details including co-signers and status.

**Use cases**: Certifications, licenses, digital credentials, notarization, professional endorsements.

---

## 11. Tokens

Tokens are digital assets you create and manage on-chain, representing value, ownership, or utility.

### 11.1 View My Tokens

Browse all tokens you have created or hold:

- Token name, ID, and status (active/closed)
- Current holders and their balances
- Creation and last modification time
- Filter by: your tokens, hidden tokens, or all tokens

### 11.2 Send Token

Transfer tokens to another FID:

1. Select the token to send.
2. Enter the recipient's FID.
3. Specify the quantity.
4. Confirm and broadcast.

Only transferable tokens can be sent.

### 11.3 Create Token

Issue a new token with:

- A unique token ID and name
- Transferability setting
- Optional pricing and access controls

### 11.4 Token History

Track all token transactions: creation events, transfers, and current circulation status.

**Use cases**: Loyalty points, membership tokens, digital collectibles, governance tokens, reward systems.

---

## 12. Multisig

Multisig allows multiple key holders to jointly control a shared FID. Transactions require a specified number of signatures before execution.

### 12.1 Create Multisig ID

1. Add members (each must have a valid FID and private key).
2. Specify the required signature count (M-of-N scheme, e.g., 2-of-3).
3. Generate the unique Multisig FID.

### 12.2 Build Multisig Transactions

Prepare a transaction from the Multisig FID:

1. Define recipients and amounts.
2. Save the unsigned transaction.
3. Distribute it to all members for signing.

### 12.3 Sign Multisig Transactions

As a member, review the pending transaction and sign it with your private key. Your signature is recorded. Once the required number of signatures is reached, the transaction can be executed.

### 12.4 View Multisig Details

View information about any Multisig FID: member list, required signature count, pending transactions, and execution history.

**Use cases**: Joint accounts, corporate treasuries, shared custody, escrow services.

---

# Part VI: For Builders

These features are primarily for developers building on the Freecash ecosystem.

## 16. Apps

Create and manage software applications built on the platform. Each app has a unique ID, owner information, version history, and links to associated services, codes, and protocols.

## 17. Services

Publish and manage services that provide specific functionality to users. Configure service type (APIP, data storage, etc.), pricing structure, access controls, and associated protocols.

## 18. Code

Share reusable code modules, smart contracts, or algorithms. Track versioning, ownership, documentation, and which services use your code.

## 19. Protocols

Define communication standards for ecosystem interoperability. Protocols specify how apps and services communicate, with version tracking and adoption metrics.

**Builder workflow**: Define a **Protocol** → Write **Code** that implements it → Create **Services** that expose functionality → Package in an **App** for distribution.

---

# Part V: Tools

## 13. QR Code

### 13.1 Scan QR Code

Launch the camera to scan QR codes from documents, images, or screens. The app automatically parses the content — supports private keys, public keys, addresses, transaction data, and text strings.

### 13.2 Generate QR Code

Convert any text, key, address, or JSON data into a QR code. Display on screen for others to scan, save to device storage, or copy to clipboard.

---

## 14. Data Converters

### 14.1 Private Key Conversion

Convert your private key between formats: Base58 Compressed, Base58 Uncompressed, Hex, and raw bytes.

### 14.2 Public Key Conversion

Transform your public key between different encoding formats and compression standards.

### 14.3 Address Conversion

Convert blockchain addresses between different encoding standards with checksum validation.

### 14.4 JSON Conversion

Pretty-print JSON for readability, minify for compact storage, and validate JSON syntax.

### 14.5 String Encoding / Decoding

Convert text between encodings: UTF-8, ASCII, Hex, Base64, and Base32.

### 14.6 Script Conversion

Convert blockchain scripts between different formats. For advanced users building custom transactions.

### 14.7 Time / Timestamp Conversion

Convert between Unix timestamps (10 and 13 digits), FCH block height, FC date format, and human-readable dates.

---

## 15. Cryptographic Tools

### 15.1 Encrypt / Decrypt

- **Encrypt**: Secure text data using symmetric encryption (password-based) or public key encryption (recipient's public key).
- **Decrypt**: Decrypt cipher text using your private key or a password/symmetric key.

### 15.2 Sign / Verify

- **Sign**: Create cryptographic signatures using ECDSA, Schnorr, or symmetric key algorithms. Proves you authored the message.
- **Verify**: Check if a signature is valid — confirm authenticity, validate the signer's public key, and ensure the message was not tampered with.

### 15.3 Hash

Generate cryptographic hashes of text using multiple algorithms (SHA256, RIPEMD160, etc.). Output in hex format. Useful for file integrity verification, digital fingerprints, and blockchain proofs.

### 15.4 Random Bytes Generator

Generate cryptographically secure random data in sizes of 1, 4, 8, 16, or 32 bytes. Output in Hex, Base58, Base32, or Decimal format. Useful for key generation, nonces, and salts.

### 15.5 TOTP (Time-based One-Time Password)

Generate time-based authentication codes compatible with Google Authenticator, Authy, etc. Displays a real-time countdown timer and generates 6-digit codes that change every 30 seconds.

---

# Part VII: Settings

## 20. Settings

- **Password Management**: Change your master password that protects keys and data.
- **Identity Management**: View your active FID/CID, switch identities, import or export keys.
- **Data Management**: Backup off-chain secrets, import/restore data, export transactions.
- **Payment Settings**: Configure transaction fee preferences.

---

## 21. Security Best Practices

### 21.1 Private Key Backup Strategies

- Back up your private key in **at least 2 different locations** using **at least 2 different methods**.
- Consider encrypted external drives, paper backups, and secure vaults.
- Store backups in physically separate locations.
- Test your backups periodically to ensure they work.

### 21.2 Password Management

- Your app password **cannot be recovered** if forgotten. You will need your private key backup to regain access.
- Use a strong password and store it in a secure password manager.
- If using passphrase-based key generation, use at least 60 characters with 6+ random characters.

### 21.3 Common Pitfalls to Avoid

- **Never share your private key** with anyone, including support staff.
- **Never display your private key** on an unsecured device or near cameras/windows.
- **Act immediately if your device is lost or compromised** — use your backup private key to transfer all assets to a new address.
- **Distinguish on-chain from off-chain data**: On-chain data (transactions, proofs, contacts) is safe on the blockchain. Off-chain data (passwords, TOTP secrets, local settings) must be backed up manually.
- **Keep your device updated** with the latest security patches and use device-level encryption.
- **Periodically review** your transaction history, contacts, and app permissions for unauthorized activity.

---

## 22. Common Issues

- **Forgot password**: You must reset the app and re-import your private key from backup. There is no password recovery.
- **Cannot send transactions**: Ensure your FID has sufficient balance and that you have a private key (not a watch-only FID).
- **Cannot receive P2P messages**: Make sure your DOCK is set up on-chain (required for offline message delivery).
- **Lost symmetric key for Room/Team**: Request the key from another member of the group.
- **Mail not arriving**: Check if the recipient has set a notice fee higher than your max paying fee.
- **Transaction stuck**: Try broadcasting again from the TX management screen. Check network connectivity.
- **Watch-only FID limitations**: Without a private key, you cannot sign transactions, send payments, carve data, or send encrypted messages. Use the offline TX workflow with the Safe app to sign externally.
