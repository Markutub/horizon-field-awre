package dev.horizonfield.core;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Thread-safe, append-only mapping from namespaced block keys ("minecraft:stone") to small ids. Id 0 is air. */
public final class MaterialPalette {
    public static final int AIR = 0;
    private final ConcurrentHashMap<String, Integer> ids = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<String> keys = new CopyOnWriteArrayList<>();

    public MaterialPalette() {
        keys.add("minecraft:air");
        ids.put("minecraft:air", AIR);
    }

    public int idOf(String key) {
        Integer v = ids.get(key);
        if (v != null) return v;
        synchronized (this) {
            v = ids.get(key);
            if (v != null) return v;
            int id = keys.size();
            if (id > Short.MAX_VALUE) throw new IllegalStateException("palette overflow");
            keys.add(key);
            ids.put(key, id);
            return id;
        }
    }

    public String keyOf(int id) {
        if (id < 0 || id >= keys.size()) return "minecraft:air";
        return keys.get(id);
    }

    public int size() { return keys.size(); }

    public List<String> snapshot() { return List.copyOf(keys); }

    /** Used when replaying persisted palette entries. Entries must arrive in id order on a fresh palette. */
    public synchronized void restore(int id, String key) {
        if (id < keys.size()) {
            if (!keys.get(id).equals(key)) throw new IllegalStateException("palette mismatch at id " + id);
            return;
        }
        if (id != keys.size()) throw new IllegalStateException("palette gap at id " + id);
        keys.add(key);
        ids.put(key, id);
    }
}
