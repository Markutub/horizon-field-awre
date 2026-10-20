package dev.horizonfield.core.stream;

/** Little's law for the unacknowledged-tile window: N = lambda * W = (bandwidth / avgTileBytes) * rtt, plus one for headroom. */
public final class InFlightSizer {
    private InFlightSizer() { }

    public static int size(double bytesPerSecond, double rttSeconds, double avgTileBytes, int min, int max) {
        if (bytesPerSecond <= 0 || rttSeconds < 0 || avgTileBytes <= 0 || min < 1 || max < min) throw new IllegalArgumentException("sizer");
        int n = (int) Math.ceil(bytesPerSecond / avgTileBytes * rttSeconds) + 1;
        return Math.max(min, Math.min(max, n));
    }
}
