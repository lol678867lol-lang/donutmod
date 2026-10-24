package de.donutmod.core;

import java.util.Map;

/** Version-unabhängige Item-Beschreibung (id z.B. "minecraft:kelp"). Haltbarkeit wird bewusst ignoriert. */
public record Stack(String id, int count, Map<String, Integer> enchants) {
    public Stack(String id, int count) { this(id, count, Map.of()); }
}
