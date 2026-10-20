package dev.horizonfield.paper.delivery;

/**
 * Seam for Phase 1b/4. Implementations decide HOW a representation reaches a vanilla client.
 * Nothing implements this yet: delivery is gated by experiments E1-E4 (docs/EXPERIMENTS.md).
 * Planned implementations: GhostChunkChannel (NMS packets), DisplayShellChannel (virtual Display entities).
 */
public interface DeliveryChannel {
    String name();

    /** True only after the corresponding experiment has passed on the running server build. */
    boolean verifiedOnThisBuild();
}
