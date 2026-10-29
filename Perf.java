package de.donutmod.fabric;

/** Automatischer Sparmodus fuer schwache PCs/Laptops (wenige Kerne oder wenig RAM). */
public final class Perf {
    private Perf() {}
    static final boolean LITE = Runtime.getRuntime().availableProcessors() <= 4
            || Runtime.getRuntime().maxMemory() < (3L << 30);
    /** Maximale Rechenzeit pro Tick fuer den Markt-Aufbau. */
    static final long BUDGET_NS = LITE ? 2_000_000L : 4_000_000L;
    static final int HOPPER_SPEED = LITE ? 4 : 12;
}
