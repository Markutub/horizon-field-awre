package dev.awre.protocol;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Tiny named-counter registry (bytes sent, rejects, ...). Key set is fixed by code, never by remote input. */
public final class Counters {
    private final ConcurrentHashMap<String, LongAdder> map = new ConcurrentHashMap<>();

    public void inc(String name) { add(name, 1); }
    public void add(String name, long n) { map.computeIfAbsent(name, k -> new LongAdder()).add(n); }
    public long get(String name) { LongAdder a = map.get(name); return a == null ? 0 : a.sum(); }

    public Map<String, Long> snapshot() {
        Map<String, Long> m = new TreeMap<>();
        map.forEach((k, v) -> m.put(k, v.sum()));
        return m;
    }
}
