package com.fc.freer.utils;

import android.content.Context;
import android.net.Uri;

import com.fc.fc_ajdk.core.crypto.KeyTools;
import com.fc.fc_ajdk.core.fch.RawTxInfo;
import com.fc.fc_ajdk.data.fchData.Cash;
import com.fc.fc_ajdk.data.feipData.Secret;
import com.fc.fc_ajdk.utils.TimberLogger;
import com.fc.freer.R;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

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
     * - [fid1,amount1,fid2,amount2,...]
     * - fid1,amount1,fid2,amount2,...
     *
     * @param context Context for string resources
     * @param input The input string to parse
     * @return ParseResult containing List of Cash objects or error message
     */
    public static ParseResult<List<Cash>> parseSendToArray(Context context, String input) {
        if (input == null || input.isEmpty()) {
            return ParseResult.error(context.getString(R.string.input_is_empty));
        }

        // Remove brackets if present
        input = input.trim();
        if (input.startsWith("[") && input.endsWith("]")) {
            input = input.substring(1, input.length() - 1);
        }

        // Split by comma
        String[] parts = input.split(",");

        // Must have even number of elements (fid, amount pairs)
        if (parts.length < 2 || parts.length % 2 != 0) {
            TimberLogger.e(TAG, "Invalid array format: must have even number of elements (fid, amount pairs)");
            return ParseResult.error(context.getString(R.string.invalid_array_format_must_be_pairs));
        }

        List<Cash> cashList = new ArrayList<>();

        for (int i = 0; i < parts.length; i += 2) {
            String fid = parts[i].trim();
            String amountStr = parts[i + 1].trim();

            // Validate FID
            if (!KeyTools.isGoodFid(fid)) {
                TimberLogger.e(TAG, "Invalid FID at position " + i + ": " + fid);
                return ParseResult.error(context.getString(R.string.invalid_fid_at_position, i / 2 + 1, fid));
            }

            // Parse amount
            try {
                double amount = Double.parseDouble(amountStr);

                if (amount < Cash.MIN_AMOUNT) {
                    TimberLogger.e(TAG, "Amount too small at position " + (i + 1) + ": " + amount);
                    return ParseResult.error(context.getString(R.string.amount_too_small_at_position, i / 2 + 1, Cash.MIN_AMOUNT));
                }

                Cash cash = new Cash(fid, amount);
                cashList.add(cash);
                TimberLogger.i(TAG, "Parsed Cash: " + fid + " -> " + amount);
            } catch (NumberFormatException e) {
                TimberLogger.e(TAG, "Invalid amount at position " + (i + 1) + ": " + amountStr);
                return ParseResult.error(context.getString(R.string.invalid_amount_at_position, i / 2 + 1, amountStr));
            }
        }

        TimberLogger.i(TAG, "Successfully parsed " + cashList.size() + " Cash objects");
        return ParseResult.success(cashList);
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
