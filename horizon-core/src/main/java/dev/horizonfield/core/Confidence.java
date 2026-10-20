package dev.horizonfield.core;

/**
 * How trustworthy a terrain description is. Predicted data must never be presented as verified data.
 * Ordinal order matters: lower ordinal = weaker evidence.
 */
public enum Confidence {
    /** No information. */
    UNKNOWN,
    /** Derived from a deterministic prediction (e.g. generator inputs). Not verified against the world. */
    PREDICTED,
    /** Sampled from real, loaded world data at {@code observedAtMillis}. */
    OBSERVED;

    public static Confidence weakest(Confidence a, Confidence b) {
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
