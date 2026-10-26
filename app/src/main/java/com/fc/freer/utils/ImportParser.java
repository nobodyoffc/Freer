package com.fc.freer.utils;

import android.content.Context;
import android.net.Uri;

import com.fc.fc_ajdk.constants.Constants;
import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.feipData.Secret;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for parsing imported data from various sources
 */
public class ImportParser {
    private static final String TAG = "ImportParser";

    /**
     * Parse result from import operation
     */
    public static class ParseResult<T> {
        public final T data;
        public final String error;
        public final boolean success;

        private ParseResult(T data, String error, boolean success) {
            this.data = data;
            this.error = error;
            this.success = success;
        }

        public static <T> ParseResult<T> success(T data) {
            return new ParseResult<>(data, null, true);
        }

        public static <T> ParseResult<T> error(String error) {
            return new ParseResult<>(null, error, false);
        }
    }

    /**
     * Parse Cash array from input string
     * Supports formats:
     * - fid1,amount1,fid2,amount2,... with or without brackets and quotes
     * - one pair per line, as from a spreadsheet: "fid amount", "fid,amount", "fid: amount"
     * - commas, full-width commas, semicolons, tabs, spaces or new lines as separators;
     *   a header line like "fid,amount" and lines starting with # or // are skipped
     * - amount before the FID, and amounts with an F or FCH unit
     * - JSON: ["fid1",1,"fid2",2], [["fid1",1],["fid2",2]], {"fid1":1,"fid2":2},
     *   [{"fid":"fid1","amount":1}] with owner/address/to/addr for the FID,
     *   value in satoshi instead of amount, and an optional lockTime, as exported Cash JSON has
     *
     * @param context Context for string resources
     * @param input The input string to parse
     * @return ParseResult containing List of Cash objects or error message
     */
    public static ParseResult<List<Cash>> parseSendToArray(Context context, String input) {
        if (input == null || input.trim().isEmpty()) {
            return ParseResult.error(context.getString(R.string.input_is_empty));
        }

        List<SendToEntry> entries = splitSendTo(input);
        if (entries == null || entries.isEmpty()) {
            TimberLogger.e(TAG, "Invalid array format: must have even number of elements (fid, amount pairs)");
            return ParseResult.error(context.getString(R.string.invalid_array_format_must_be_pairs));
        }

        List<Cash> cashList = new ArrayList<>();

        for (int i = 0; i < entries.size(); i++) {
            SendToEntry entry = entries.get(i);
            int position = i + 1;

            // Validate FID
            if (!KeyTools.isGoodFid(entry.fid)) {
                TimberLogger.e(TAG, "Invalid FID at position " + position + ": " + entry.fid);
                return ParseResult.error(context.getString(R.string.invalid_fid_at_position, position, entry.fid));
            }

            // Parse amount
            Double amount = parseAmount(entry.amount);
            if (amount == null || amount > Constants.MAX_AMOUNT) {
                TimberLogger.e(TAG, "Invalid amount at position " + position + ": " + entry.amount);
                return ParseResult.error(context.getString(R.string.invalid_amount_at_position, position, entry.amount));
            }
            if (amount < Cash.MIN_AMOUNT) {
                TimberLogger.e(TAG, "Amount too small at position " + position + ": " + amount);
                return ParseResult.error(context.getString(R.string.amount_too_small_at_position, position, Cash.MIN_AMOUNT));
            }

            Cash cash = new Cash(entry.fid, amount);
            if (entry.lockTime != null && entry.lockTime > 0) cash.setLockTime(entry.lockTime);
            cashList.add(cash);
            TimberLogger.i(TAG, "Parsed Cash: " + entry.fid + " -> " + amount);
        }

        TimberLogger.i(TAG, "Successfully parsed " + cashList.size() + " Cash objects");
        return ParseResult.success(cashList);
    }

    /** One pay-to entry before validation. */
    static final class SendToEntry {
        final String fid;
        final String amount;
        final Long lockTime;

        SendToEntry(String fid, String amount, Long lockTime) {
            this.fid = fid == null ? "" : fid.trim();
            this.amount = amount == null ? "" : amount.trim();
            this.lockTime = lockTime;
        }
    }

    private static final String[] FID_KEYS = {"fid", "owner", "address", "addr", "to"};
    private static final Pattern AMOUNT_PATTERN =
            Pattern.compile("^\\+?((?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?)\\s*(?:F|FCH)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TEXT_SEPARATORS =
            Pattern.compile("[\\s,\uFF0C;\uFF1B:\uFF1A=|\"'\u201C\u201D\u2018\u2019\\[\\]{}()\u3001]+");

    /**
     * Split the input into FID and amount entries without validating them.
     * @return null if the elements do not pair up
     */
    static List<SendToEntry> splitSendTo(String input) {
        String trimmed = input.trim();
        if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            try {
                List<SendToEntry> entries = splitSendToJson(JsonParser.parseString(trimmed));
                if (entries != null) return entries;
            } catch (Exception e) {
                TimberLogger.d(TAG, "Send to array is not JSON, reading it as text");
            }
        }
        return splitSendToText(trimmed);
    }

    private static List<SendToEntry> splitSendToJson(JsonElement root) {
        List<SendToEntry> entries = new ArrayList<>();
        if (root.isJsonObject()) {
            JsonObject object = root.getAsJsonObject();
            if (findFid(object) != null) {
                entries.add(entryFromObject(object));
                return entries;
            }
            // {"fid1": amount1, "fid2": amount2}
            for (Map.Entry<String, JsonElement> e : object.entrySet()) {
                if (!e.getValue().isJsonPrimitive()) return null;
                entries.add(new SendToEntry(e.getKey(), e.getValue().getAsString(), null));
            }
            return entries;
        }
        if (!root.isJsonArray()) return null;

        List<String> flat = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray()) {
            if (element.isJsonObject()) {
                entries.add(entryFromObject(element.getAsJsonObject()));
            } else if (element.isJsonArray()) {
                JsonArray pair = element.getAsJsonArray();
                if (pair.size() != 2 || !pair.get(0).isJsonPrimitive() || !pair.get(1).isJsonPrimitive()) return null;
                entries.add(orderedEntry(pair.get(0).getAsString(), pair.get(1).getAsString()));
            } else if (element.isJsonPrimitive()) {
                flat.add(element.getAsString());
            }
        }
        List<SendToEntry> flatEntries = pairTokens(flat);
        if (flatEntries == null) return null;
        entries.addAll(flatEntries);
        return entries;
    }

    private static String findFid(JsonObject object) {
        for (String key : FID_KEYS) {
            JsonElement value = object.get(key);
            if (value != null && value.isJsonPrimitive()) return value.getAsString();
        }
        return null;
    }

    private static SendToEntry entryFromObject(JsonObject object) {
        String amount = null;
        JsonElement amountElement = object.get("amount");
        JsonElement valueElement = object.get("value");
        if (amountElement != null && amountElement.isJsonPrimitive()) {
            amount = amountElement.getAsString();
        } else if (valueElement != null && valueElement.isJsonPrimitive()) {
            // Cash JSON keeps the amount in satoshi as value
            try {
                amount = new BigDecimal(valueElement.getAsString()).movePointLeft(8).toPlainString();
            } catch (NumberFormatException e) {
                amount = valueElement.getAsString();
            }
        }
        Long lockTime = null;
        JsonElement lockTimeElement = object.get("lockTime");
        if (lockTimeElement != null && lockTimeElement.isJsonPrimitive()) {
            try {
                lockTime = lockTimeElement.getAsLong();
            } catch (NumberFormatException e) {
                lockTime = null;
            }
        }
        return new SendToEntry(findFid(object), amount, lockTime);
    }

    private static List<SendToEntry> splitSendToText(String input) {
        List<String> tokens = new ArrayList<>();
        for (String line : input.split("\\r?\\n|\\r")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;

            List<String> lineTokens = new ArrayList<>();
            boolean hasData = false;
            for (String token : TEXT_SEPARATORS.split(line)) {
                if (token.isEmpty() || token.equalsIgnoreCase("F") || token.equalsIgnoreCase("FCH")) continue;
                lineTokens.add(token);
                if (KeyTools.isGoodFid(token) || parseAmount(token) != null) hasData = true;
            }
            // A header line such as "fid,amount" has neither
            if (hasData) tokens.addAll(lineTokens);
        }
        return pairTokens(tokens);
    }

    private static List<SendToEntry> pairTokens(List<String> tokens) {
        if (tokens.size() % 2 != 0) return null;
        List<SendToEntry> entries = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i += 2) {
            entries.add(orderedEntry(tokens.get(i), tokens.get(i + 1)));
        }
        return entries;
    }

    /** Accept the amount before the FID too. */
    private static SendToEntry orderedEntry(String first, String second) {
        if (!KeyTools.isGoodFid(first.trim()) && KeyTools.isGoodFid(second.trim())) {
            return new SendToEntry(second, first, null);
        }
        return new SendToEntry(first, second, null);
    }

    /** @return the amount in FCH, or null if it is not a positive number with an optional F or FCH unit */
    static Double parseAmount(String amount) {
        if (amount == null) return null;
        Matcher matcher = AMOUNT_PATTERN.matcher(amount.trim());
        if (!matcher.matches()) return null;
        try {
            double value = Double.parseDouble(matcher.group(1));
            return Double.isNaN(value) || Double.isInfinite(value) ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parse RawTxInfo from input string
     *
     * @param context Context for string resources
     * @param input The input string (JSON format)
     * @param liveFid The current live FID to validate sender
     * @return ParseResult containing RawTxInfo or error message
     */
    public static ParseResult<RawTxInfo> parseTxInfo(Context context, String input, String liveFid) {
        if (input == null || input.isEmpty()) {
            return ParseResult.error(context.getString(R.string.input_is_empty));
        }

        try {
            RawTxInfo rawTxInfo;
            try {
                rawTxInfo = RawTxInfo.fromJson(input, RawTxInfo.class);

                if (rawTxInfo == null) {
                    rawTxInfo = RawTxInfo.fromRawTxForCs(input);
                }

                if (rawTxInfo == null) {
                    TimberLogger.e(TAG, "Failed to parse TX");
                    return ParseResult.error(context.getString(R.string.failed_to_parse_json));
                }

                // Validate sender
                if (liveFid != null && rawTxInfo.getSender() != null && !rawTxInfo.getSender().equals(liveFid)) {
                    TimberLogger.d(TAG, "Sender is not the live fid");
                    return ParseResult.error(context.getString(R.string.it_is_not_your_tx));
                }
            } catch (Exception e) {
                try {
                    rawTxInfo = RawTxInfo.fromRawTxForCs(input);
                } catch (Exception e1) {
                    TimberLogger.e(TAG, "Failed to parse TX");
                    return ParseResult.error(context.getString(R.string.failed_to_parse_json));
                }
                if (rawTxInfo == null) {
                    TimberLogger.e(TAG, "Failed to parse TX");
                    return ParseResult.error(context.getString(R.string.failed_to_parse_json));
                }
            }

            return ParseResult.success(rawTxInfo);
        } catch (Exception e) {
            TimberLogger.e(TAG, "Error parsing JSON: " + e.getMessage());
            return ParseResult.error(context.getString(R.string.invalid_json_format));
        }
    }

    /**
     * Parse TOTP secret from input string
     * Supports formats:
     * - JSON: {"secret": "...", "label": "..."}
     * - URI: otpauth://totp/account?secret=...&issuer=...
     *
     * @param context Context for string resources
     * @param input The input string to parse
     * @return ParseResult containing Secret or error message
     */
    public static ParseResult<Secret> parseTotpSecret(Context context, String input) {
        if (input == null || input.isEmpty()) {
            return ParseResult.error(context.getString(R.string.input_is_empty));
        }

        // Try parsing as JSON first
        try {
            JsonObject jsonObject = JsonParser.parseString(input).getAsJsonObject();
            if (jsonObject.has("secret") && jsonObject.has("label")) {
                Secret secretDetail = new Secret();
                String label = jsonObject.get("label").getAsString();
                if (label.contains(" - ")) {
                    label = label.split(" - ")[1];
                }

                if (label.contains(":") && !label.contains(": ")) {
                    label = label.replaceAll(":", ": ");
                }
                secretDetail.setTitle(label);
                String secret = jsonObject.get("secret").getAsString();
                if (secret == null || secret.isEmpty()) {
                    return ParseResult.error(context.getString(R.string.invalid_secret_format));
                }
                secretDetail.setContent(secret);
                secretDetail.setType("TOTP");
                return ParseResult.success(secretDetail);
            }
        } catch (Exception e) {
            // Not a valid JSON, continue to try other formats
        }

        // Try parsing as URI
        try {
            if (input.startsWith("otpauth://totp/")) {
                Uri uri = Uri.parse(input);
                String path = uri.getPath();
                if (path != null) {
                    String account = path.substring(1); // Remove leading "/"
                    String secret = uri.getQueryParameter("secret");
                    String issuer = uri.getQueryParameter("issuer");
                    String title;
                    if (issuer == null || issuer.isEmpty()) {
                        title = account;
                    } else {
                        title = issuer + ": " + account;
                    }
                    if (secret != null) {
                        Secret secretDetail = new Secret();
                        secretDetail.setTitle(title);
                        secretDetail.setContent(secret);
                        secretDetail.setType(Secret.Type.TOTP.displayName);
                        secretDetail.setOnChain(false);
                        return ParseResult.success(secretDetail);
                    }
                }
            }
        } catch (Exception e) {
            // Not a valid URI
        }

        return ParseResult.error(context.getString(R.string.invalid_totp_format));
    }
}
