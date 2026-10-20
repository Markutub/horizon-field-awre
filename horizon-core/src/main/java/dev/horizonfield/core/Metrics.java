package dev.horizonfield.core;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Tiny lock-free counter registry for diagnostics. */
public final class Metrics {
    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();

    public void inc(String name) { add(name, 1); }
    public void add(String name, long n) { counters.computeIfAbsent(name, k -> new LongAdder()).add(n); }
    public long get(String name) { LongAdder a = counters.get(name); return a == null ? 0 : a.sum(); }

    public Map<String, Long> snapshot() {
        Map<String, Long> m = new TreeMap<>();
        counters.forEach((k, v) -> m.put(k, v.sum()));
        return m;
    }
}
