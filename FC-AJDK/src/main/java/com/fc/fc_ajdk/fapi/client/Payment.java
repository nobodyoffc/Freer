package com.fc.fc_ajdk.fapi.client;

public class Payment {
    private String id;        // Transaction ID
    private Long time;        // Timestamp in milliseconds
    private Double amount;    // Amount in FCH coins

    public Payment() {
    }

    public Payment(String id, Long time, Double amount) {
        this.id = id;
        this.time = time;
        this.amount = amount;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getTime() {
        return time;
    }

    public void setTime(Long time) {
        this.time = time;
    }

    public Double getAmount() {
        return amount;
    }

    public void setAmount(Double amount) {
        this.amount = amount;
    }

    /**
     * Check if this payment was made within the last specified minutes
     * @param minutes Number of minutes to check
     * @return true if payment is within the time window
     */
    public boolean isWithinMinutes(int minutes) {
        if (time == null) return false;
        long currentTime = System.currentTimeMillis();
        long minutesInMillis = minutes * 60 * 1000L;
        return (currentTime - time) < minutesInMillis;
    }

    /**
     * Check if this payment was made within the last 24 hours
     * @return true if payment is within the last day
     */
    public boolean isWithinLastDay() {
        return isWithinMinutes(24*60);
    }

    @Override
    public String toString() {
        return "Payment{" +
                "id='" + id + '\'' +
                ", time=" + time +
                ", amount=" + amount +
                '}';
    }
}
