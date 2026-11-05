package com.fc.fc_ajdk.data.feipData;

import java.util.List;

/**
 * The {@code data} of a FEIP24 Image op: publish, update, delete, recover or rate.
 *
 * <p>An update replaces every field of the record, nulls included, so callers pass the
 * record's current values for anything they are not changing (FEIP24 Operations, update).
 * The subject id is never set on publish: the record's id is the publish txid.
 */
public class ImageOpData {
    private String imageId;
    private List<String> imageIds;
    private String op;
    private String title;
    private String did;
    private List<String> authors;
    private String lang;
    private String format;
    private String summary;
    private List<String> locas;
    private Integer rate;
    private String cause;

    public enum Op {
        PUBLISH(FeipOp.PUBLISH),
        UPDATE(FeipOp.UPDATE),
        DELETE(FeipOp.DELETE),
        RECOVER(FeipOp.RECOVER),
        RATE(FeipOp.RATE);

        private final FeipOp feipOp;

        Op(FeipOp feipOp) {
            this.feipOp = feipOp;
        }

        public String toLowerCase() {
            return feipOp.getValue().toLowerCase();
        }
    }

    public static ImageOpData makePublish(String title, String did, String lang, List<String> authors,
                                          String format, String summary, List<String> locas) {
        ImageOpData data = new ImageOpData();
        data.setOp(Op.PUBLISH.toLowerCase());
        data.fill(title, did, lang, authors, format, summary, locas);
        return data;
    }

    public static ImageOpData makeUpdate(String imageId, String title, String did, String lang, List<String> authors,
                                         String format, String summary, List<String> locas) {
        ImageOpData data = new ImageOpData();
        data.setOp(Op.UPDATE.toLowerCase());
        data.setImageId(imageId);
        data.fill(title, did, lang, authors, format, summary, locas);
        return data;
    }

    public static ImageOpData makeDelete(List<String> imageIds) {
        ImageOpData data = new ImageOpData();
        data.setOp(Op.DELETE.toLowerCase());
        data.setImageIds(imageIds);
        return data;
    }

    public static ImageOpData makeRecover(List<String> imageIds) {
        ImageOpData data = new ImageOpData();
        data.setOp(Op.RECOVER.toLowerCase());
        data.setImageIds(imageIds);
        return data;
    }

    /** @param cause optional; pass null when blank so the field is omitted, never carved as "". */
    public static ImageOpData makeRate(String imageId, Integer rate, String cause) {
        ImageOpData data = new ImageOpData();
        data.setOp(Op.RATE.toLowerCase());
        data.setImageId(imageId);
        data.setRate(rate);
        data.setCause(cause);
        return data;
    }

    private void fill(String title, String did, String lang, List<String> authors,
                      String format, String summary, List<String> locas) {
        this.title = title;
        this.did = blankToNull(did);
        this.lang = blankToNull(lang);
        this.authors = authors == null || authors.isEmpty() ? null : authors;
        this.format = blankToNull(format);
        this.summary = blankToNull(summary);
        this.locas = locas == null || locas.isEmpty() ? null : locas;
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value;
    }

    public String getImageId() { return imageId; }
    public void setImageId(String imageId) { this.imageId = imageId; }
    public List<String> getImageIds() { return imageIds; }
    public void setImageIds(List<String> imageIds) { this.imageIds = imageIds; }
    public String getOp() { return op; }
    public void setOp(String op) { this.op = op; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDid() { return did; }
    public void setDid(String did) { this.did = did; }
    public List<String> getAuthors() { return authors; }
    public void setAuthors(List<String> authors) { this.authors = authors; }
    public String getLang() { return lang; }
    public void setLang(String lang) { this.lang = lang; }
    public String getFormat() { return format; }
    public void setFormat(String format) { this.format = format; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public List<String> getLocas() { return locas; }
    public void setLocas(List<String> locas) { this.locas = locas; }
    public Integer getRate() { return rate; }
    public void setRate(Integer rate) { this.rate = rate; }
    public String getCause() { return cause; }
    public void setCause(String cause) { this.cause = cause; }
}
