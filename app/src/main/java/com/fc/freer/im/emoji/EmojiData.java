package com.fc.freer.im.emoji;

import com.fc.freer.R;

import java.util.LinkedHashMap;
import java.util.Map;

public class EmojiData {

    public static final Map<EmojiCategory, String[]> EMOJI_MAP = new LinkedHashMap<>();

    static {
        EMOJI_MAP.put(
            new EmojiCategory("smileys", R.string.smileys_people),
            new String[]{
                "\uD83D\uDE00", "\uD83D\uDE03", "\uD83D\uDE04", "\uD83D\uDE01", "\uD83D\uDE06",
                "\uD83D\uDE05", "\uD83D\uDE02", "\uD83E\uDD23", "\uD83D\uDE0A", "\uD83D\uDE07",
                "\uD83D\uDE42", "\uD83D\uDE43", "\uD83D\uDE09", "\uD83D\uDE0C", "\uD83D\uDE0D",
                "\uD83E\uDD70", "\uD83D\uDE18", "\uD83D\uDE17", "\uD83D\uDE1A", "\uD83D\uDE19",
                "\uD83D\uDE0B", "\uD83D\uDE1B", "\uD83D\uDE1C", "\uD83E\uDD2A", "\uD83D\uDE1D",
                "\uD83E\uDD11", "\uD83E\uDD17", "\uD83E\uDD2D", "\uD83E\uDD2B", "\uD83E\uDD14",
                "\uD83E\uDD10", "\uD83E\uDD28", "\uD83D\uDE10", "\uD83D\uDE11", "\uD83D\uDE36",
                "\uD83D\uDE0F", "\uD83D\uDE12", "\uD83D\uDE44", "\uD83D\uDE2C", "\uD83E\uDD25",
                "\uD83D\uDE0E", "\uD83E\uDD13", "\uD83E\uDD78", "\uD83E\uDD29", "\uD83E\uDD73",
                "\uD83D\uDE15", "\uD83D\uDE1F", "\uD83D\uDE41", "\uD83D\uDE2E", "\uD83D\uDE2F",
                "\uD83D\uDE32", "\uD83D\uDE33", "\uD83E\uDD7A", "\uD83D\uDE26", "\uD83D\uDE27",
                "\uD83D\uDE28", "\uD83D\uDE30", "\uD83D\uDE25", "\uD83D\uDE22", "\uD83D\uDE2D",
                "\uD83D\uDE31", "\uD83D\uDE16", "\uD83D\uDE23", "\uD83D\uDE1E", "\uD83D\uDE13",
                "\uD83D\uDE29", "\uD83D\uDE24", "\uD83D\uDE20", "\uD83D\uDE21", "\uD83E\uDD2C",
                "\uD83D\uDE08", "\uD83D\uDC7F", "\uD83D\uDC80", "\u2620\uFE0F", "\uD83D\uDCA9",
                "\uD83E\uDD21", "\uD83D\uDC7B", "\uD83D\uDC7D", "\uD83E\uDD16", "\uD83D\uDE3A",
                "\uD83D\uDC4D", "\uD83D\uDC4E", "\uD83D\uDC4A", "\u270A", "\uD83E\uDD1B",
                "\uD83E\uDD1C", "\uD83D\uDC4F", "\uD83D\uDE4C", "\uD83D\uDC4B", "\uD83E\uDD1A",
                "\uD83D\uDC4C", "\u270C\uFE0F", "\uD83E\uDD1E", "\uD83E\uDD1F", "\uD83E\uDD18",
                "\uD83D\uDE4F", "\u2764\uFE0F", "\uD83D\uDC94", "\uD83D\uDC95", "\uD83D\uDC96"
            }
        );

        EMOJI_MAP.put(
            new EmojiCategory("animals", R.string.animals_nature),
            new String[]{
                "\uD83D\uDC36", "\uD83D\uDC31", "\uD83D\uDC2D", "\uD83D\uDC39", "\uD83D\uDC30",
                "\uD83E\uDD8A", "\uD83D\uDC3B", "\uD83D\uDC3C", "\uD83D\uDC28", "\uD83D\uDC2F",
                "\uD83E\uDD81", "\uD83D\uDC2E", "\uD83D\uDC37", "\uD83D\uDC38", "\uD83D\uDC35",
                "\uD83D\uDC14", "\uD83D\uDC27", "\uD83D\uDC26", "\uD83E\uDD85", "\uD83E\uDD86",
                "\uD83E\uDD89", "\uD83D\uDC3A", "\uD83D\uDC17", "\uD83D\uDC34", "\uD83E\uDD84",
                "\uD83D\uDC1D", "\uD83D\uDC1B", "\uD83E\uDD8B", "\uD83D\uDC0C", "\uD83D\uDC1A",
                "\uD83D\uDC1E", "\uD83D\uDC1C", "\uD83E\uDD97", "\uD83E\uDD82", "\uD83D\uDC22",
                "\uD83D\uDC0D", "\uD83E\uDD8E", "\uD83E\uDD96", "\uD83E\uDD95", "\uD83D\uDC19",
                "\uD83E\uDD91", "\uD83E\uDD90", "\uD83E\uDD9E", "\uD83D\uDC20", "\uD83D\uDC1F",
                "\uD83D\uDC21", "\uD83D\uDC2C", "\uD83D\uDC33", "\uD83E\uDD88", "\uD83D\uDC0A",
                "\uD83C\uDF38", "\uD83C\uDF39", "\uD83C\uDF3A", "\uD83C\uDF3B", "\uD83C\uDF3C",
                "\uD83C\uDF37", "\uD83C\uDF31", "\uD83C\uDF32", "\uD83C\uDF33", "\uD83C\uDF34",
                "\uD83C\uDF35", "\uD83C\uDF3E", "\uD83C\uDF3F", "\u2618\uFE0F", "\uD83C\uDF40",
                "\uD83C\uDF41", "\uD83C\uDF42", "\uD83C\uDF43", "\uD83C\uDF44", "\uD83D\uDC90"
            }
        );

        EMOJI_MAP.put(
            new EmojiCategory("food", R.string.food_drink),
            new String[]{
                "\uD83C\uDF4E", "\uD83C\uDF4F", "\uD83C\uDF4A", "\uD83C\uDF4B", "\uD83C\uDF4C",
                "\uD83C\uDF49", "\uD83C\uDF47", "\uD83C\uDF53", "\uD83C\uDF48", "\uD83C\uDF50",
                "\uD83C\uDF51", "\uD83C\uDF52", "\uD83C\uDF45", "\uD83E\uDD65", "\uD83E\uDD51",
                "\uD83C\uDF46", "\uD83E\uDD54", "\uD83E\uDD55", "\uD83C\uDF3D", "\uD83C\uDF36",
                "\uD83E\uDD52", "\uD83E\uDD66", "\uD83C\uDF44", "\uD83E\uDD5C", "\uD83C\uDF30",
                "\uD83C\uDF5E", "\uD83E\uDD50", "\uD83C\uDF6F", "\uD83E\uDD5B", "\uD83C\uDF54",
                "\uD83C\uDF5F", "\uD83C\uDF55", "\uD83C\uDF2D", "\uD83E\uDD6A", "\uD83C\uDF2E",
                "\uD83C\uDF2F", "\uD83E\uDD59", "\uD83E\uDD5A", "\uD83C\uDF73", "\uD83E\uDD58",
                "\uD83C\uDF72", "\uD83E\uDD63", "\uD83E\uDD57", "\uD83C\uDF7F", "\uD83C\uDF71",
                "\uD83C\uDF58", "\uD83C\uDF59", "\uD83C\uDF5A", "\uD83C\uDF5B", "\uD83C\uDF5C",
                "\uD83C\uDF70", "\uD83C\uDF82", "\uD83C\uDF67", "\uD83C\uDF68", "\uD83C\uDF69",
                "\u2615", "\uD83C\uDF75", "\uD83C\uDF76", "\uD83C\uDF7A", "\uD83C\uDF7B",
                "\uD83E\uDD42", "\uD83C\uDF77", "\uD83E\uDD43", "\uD83C\uDF78", "\uD83C\uDF79"
            }
        );

        EMOJI_MAP.put(
            new EmojiCategory("activities", R.string.activities),
            new String[]{
                "\u26BD", "\uD83C\uDFC0", "\uD83C\uDFC8", "\u26BE", "\uD83E\uDD4E",
                "\uD83C\uDFBE", "\uD83C\uDFD0", "\uD83C\uDFC9", "\uD83E\uDD4F", "\uD83C\uDFB1",
                "\uD83C\uDFD3", "\uD83C\uDFF8", "\uD83C\uDFD2", "\uD83E\uDD4D", "\uD83C\uDFCF",
                "\u26F3", "\uD83C\uDFF9", "\uD83C\uDFA3", "\uD83E\uDD3C", "\uD83E\uDD4A",
                "\uD83E\uDD4B", "\u26F8\uFE0F", "\uD83C\uDFBF", "\u26F7\uFE0F", "\uD83C\uDFC2",
                "\uD83C\uDFCB\uFE0F", "\uD83E\uDD3A", "\uD83E\uDD38", "\u26F9\uFE0F", "\uD83E\uDD3E",
                "\uD83C\uDFC4", "\uD83C\uDFCA", "\uD83D\uDEB4", "\uD83D\uDEB5", "\uD83C\uDFC7",
                "\uD83C\uDFC6", "\uD83E\uDD47", "\uD83E\uDD48", "\uD83E\uDD49", "\uD83C\uDFC5",
                "\uD83C\uDFAD", "\uD83C\uDFA8", "\uD83C\uDFAC", "\uD83C\uDFA4", "\uD83C\uDFA7",
                "\uD83C\uDFB5", "\uD83C\uDFB6", "\uD83C\uDFB9", "\uD83C\uDFBA", "\uD83C\uDFBB"
            }
        );

        EMOJI_MAP.put(
            new EmojiCategory("travel", R.string.travel_places),
            new String[]{
                "\uD83D\uDE97", "\uD83D\uDE95", "\uD83D\uDE99", "\uD83D\uDE8C", "\uD83D\uDE8E",
                "\uD83C\uDFCE\uFE0F", "\uD83D\uDE93", "\uD83D\uDE91", "\uD83D\uDE92", "\uD83D\uDE90",
                "\uD83D\uDE9A", "\uD83D\uDE9B", "\uD83D\uDE9C", "\uD83D\uDEF5", "\uD83D\uDEB2",
                "\uD83D\uDE81", "\u2708\uFE0F", "\uD83D\uDE80", "\uD83D\uDEF8", "\uD83D\uDEA2",
                "\u26F5", "\uD83D\uDEA4", "\uD83D\uDE82", "\uD83D\uDE83", "\uD83D\uDE84",
                "\uD83D\uDE85", "\uD83D\uDE86", "\uD83D\uDE88", "\uD83D\uDE89", "\uD83C\uDFE0",
                "\uD83C\uDFE2", "\uD83C\uDFE5", "\uD83C\uDFEB", "\uD83C\uDFEA", "\uD83C\uDFE8",
                "\uD83C\uDFE9", "\u26EA", "\uD83D\uDD4C", "\uD83D\uDD4D", "\u26E9\uFE0F",
                "\uD83C\uDFF0", "\uD83C\uDFEF", "\uD83C\uDDFA\uD83C\uDDF8", "\uD83C\uDF05",
                "\uD83C\uDF04", "\uD83C\uDF03", "\uD83C\uDF06", "\uD83C\uDF07", "\uD83C\uDF09",
                "\u2600\uFE0F", "\uD83C\uDF24\uFE0F", "\u26C5", "\uD83C\uDF25\uFE0F", "\uD83C\uDF26\uFE0F",
                "\u2601\uFE0F", "\uD83C\uDF27\uFE0F", "\u26C8\uFE0F", "\uD83C\uDF29\uFE0F", "\u2744\uFE0F",
                "\uD83C\uDF19", "\uD83C\uDF1A", "\uD83C\uDF1B", "\uD83C\uDF1C", "\uD83C\uDF1D"
            }
        );

        EMOJI_MAP.put(
            new EmojiCategory("objects", R.string.objects),
            new String[]{
                "\u231A", "\uD83D\uDCF1", "\uD83D\uDCBB", "\u2328\uFE0F", "\uD83D\uDCBD",
                "\uD83D\uDCBE", "\uD83D\uDCBF", "\uD83D\uDCC0", "\uD83D\uDCF7", "\uD83D\uDCF9",
                "\uD83C\uDFA5", "\uD83D\uDCFD\uFE0F", "\uD83D\uDCFA", "\uD83D\uDCFB", "\uD83D\uDD0A",
                "\uD83D\uDD14", "\uD83D\uDCE3", "\uD83D\uDCE2", "\uD83D\uDCEF", "\uD83D\uDD0D",
                "\uD83D\uDD0E", "\uD83D\uDCA1", "\uD83D\uDD26", "\uD83D\uDCD5", "\uD83D\uDCD7",
                "\uD83D\uDCD8", "\uD83D\uDCD9", "\uD83D\uDCDA", "\uD83D\uDCD3", "\uD83D\uDCD2",
                "\uD83D\uDCC4", "\uD83D\uDCF0", "\uD83D\uDCB0", "\uD83D\uDCB3", "\uD83D\uDCB5",
                "\u2709\uFE0F", "\uD83D\uDCE7", "\uD83D\uDCE8", "\uD83D\uDCE9", "\uD83D\uDCEE",
                "\u270F\uFE0F", "\u2712\uFE0F", "\uD83D\uDCDD", "\uD83D\uDD12", "\uD83D\uDD13",
                "\uD83D\uDD11", "\uD83D\uDEE0\uFE0F", "\u2699\uFE0F", "\uD83D\uDD27", "\uD83D\uDD28"
            }
        );

        EMOJI_MAP.put(
            new EmojiCategory("symbols", R.string.symbols),
            new String[]{
                "\u2764\uFE0F", "\uD83E\uDDE1", "\uD83D\uDC9B", "\uD83D\uDC9A", "\uD83D\uDC99",
                "\uD83D\uDC9C", "\uD83D\uDDA4", "\uD83D\uDC94", "\u2763\uFE0F", "\uD83D\uDC95",
                "\uD83D\uDC9E", "\uD83D\uDC93", "\uD83D\uDC97", "\uD83D\uDC96", "\uD83D\uDC98",
                "\uD83D\uDC9D", "\u2B50", "\uD83C\uDF1F", "\uD83D\uDCAB", "\u2728",
                "\uD83D\uDD25", "\uD83D\uDCA5", "\uD83C\uDF88", "\uD83C\uDF89", "\uD83C\uDF8A",
                "\u2705", "\u274C", "\u2753", "\u2757", "\u203C\uFE0F",
                "\u2049\uFE0F", "\uD83D\uDCAF", "\uD83D\uDD1E", "\uD83D\uDEAB", "\uD83C\uDD97",
                "\uD83C\uDD99", "\uD83C\uDD92", "\uD83C\uDD95", "\uD83C\uDD93", "\u2139\uFE0F",
                "\uD83D\uDD34", "\uD83D\uDFE0", "\uD83D\uDFE1", "\uD83D\uDFE2", "\uD83D\uDD35",
                "\uD83D\uDFE3", "\u26AA", "\u26AB", "\uD83D\uDFE4", "\u25B6\uFE0F"
            }
        );
    }

    public static class EmojiCategory {
        public final String id;
        public final int labelResId;

        public EmojiCategory(String id, int labelResId) {
            this.id = id;
            this.labelResId = labelResId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EmojiCategory)) return false;
            return id.equals(((EmojiCategory) o).id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }
}
