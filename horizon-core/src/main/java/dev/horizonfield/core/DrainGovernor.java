package dev.horizonfield.core;

/**
 * Bounds the AVERAGE time a repeated job takes from a latency-critical thread (the Paper main thread), whatever one job costs.
 *
 * <p>A fixed "stop after 0.5 ms" check is only evaluated before each job, so one job that costs 5 ms still runs: with snapshots whose
 * p99 is several milliseconds the per-tick cost has a heavy tail, and anything done outside the drain loop (a sweep that lists every
 * loaded chunk) is not counted at all. Here every tick earns {@code budgetPerTick} nanoseconds of credit (capped, so idle time cannot
 * be banked into a burst), a job may start only while the credit covers its expected cost (an EWMA of what jobs really cost), and the
 * measured cost of each job (and of other main-thread work done for the same purpose) is charged afterwards.
 *
 * <p>Guarantees, pinned by DrainGovernorTest: over N ticks the charged time never exceeds
 * {@code N * budgetPerTick + maxCredit + maxCharge}; a job costing more than the budget still runs, only less often (about once every
 * cost/budget ticks), so progress never stops; one huge outlier (a GC pause counted inside a job) delays the next job by at most
 * {@code (maxCharge + maxCredit) / budgetPerTick} ticks because a single charge is capped. Single-threaded: use from one thread.
 */
public final class DrainGovernor {
    private final long budgetPerTick;
    private final long maxCredit;
    private final long maxCharge;
    private long credit;
    private double estimate;

    /** @param budgetPerTickNanos average time per tick the job may take; @param initialEstimateNanos expected cost of one job before any is measured */
    public DrainGovernor(long budgetPerTickNanos, long initialEstimateNanos) {
        if (budgetPerTickNanos <= 0) throw new IllegalArgumentException("budget must be positive");
        this.budgetPerTick = budgetPerTickNanos;
        this.maxCredit = 8 * budgetPerTickNanos;
        this.maxCharge = 20 * budgetPerTickNanos;
        this.estimate = Math.max(0, initialEstimateNanos);
        this.credit = budgetPerTickNanos;
    }

    /** Call once per tick, before any job. */
    public void onTick() { credit = Math.min(maxCredit, credit + budgetPerTick); }

    /** True when the credit covers the expected cost of one more job. The expectation is capped at the credit ceiling, so a job that is always expensive is not starved. */
    public boolean mayStart() { return credit >= Math.min((long) estimate, maxCredit); }

    /** Charge the measured cost of a job (or of related main-thread work); updates the expected cost. */
    public void record(long actualNanos) {
        long c = Math.max(0, Math.min(actualNanos, maxCharge));
        credit -= c;
        estimate = 0.8 * estimate + 0.2 * c;
    }

    public long creditNanos() { return credit; }
    public long expectedCostNanos() { return (long) estimate; }
    public long budgetPerTickNanos() { return budgetPerTick; }
    public long maxCreditNanos() { return maxCredit; }
    public long maxChargeNanos() { return maxCharge; }
}
