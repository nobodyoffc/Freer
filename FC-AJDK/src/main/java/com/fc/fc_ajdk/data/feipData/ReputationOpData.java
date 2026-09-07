package com.fc.fc_ajdk.data.feipData;

/**
 * The {@code data} of a FEIP16 (Reputation) carve.
 *
 * <p><b>The ratee is {@code fid}, and it belongs here.</b> It used to be
 * put in the envelope's {@code did}, which is FEIP0's <i>document</i> id
 * — an identifier for an on-chain record, not for a party — so the
 * parser never found a ratee there and every rating built that way
 * confirmed, cost its sender the fee and the CoinDays, and changed
 * nobody's score. It is a payload field now: it says what it means, and
 * it costs nothing to carry.
 *
 * <p>Nor is the ratee taken from the transaction's recipient output. A
 * rating is an opinion, not a payment; reading the payee as the subject
 * would mean you cannot rate anyone without paying them, and a rating
 * would be lost whenever the output was absent or reordered.
 *
 * <p>{@code rate} MUST be {@code good} or {@code bad} — see
 * {@code Values.GOOD} / {@code Values.BAD}. Any other string carries no
 * reputation delta.
 */
public class ReputationOpData {

	/** The rated FID. Required; without it the operation is ignored. */
	private String fid;
	private String rate;
	private String cause;

	public String getFid() {
		return fid;
	}

	public void setFid(String fid) {
		this.fid = fid;
	}

	public String getRate() {
		return rate;
	}

	public void setRate(String rate) {
		this.rate = rate;
	}

	public String getCause() {
		return cause;
	}

	public void setCause(String cause) {
		this.cause = cause;
	}

	/**
	 * @param fid   the FID being rated
	 * @param rate  {@code good} or {@code bad}
	 * @param cause optional free text; pass null when blank so Gson omits
	 *              the field rather than carving an empty string
	 */
	public static ReputationOpData makeRate(String fid, String rate, String cause) {
		ReputationOpData data = new ReputationOpData();
		data.setFid(fid);
		data.setRate(rate);
		data.setCause(cause);
		return data;
	}
}
