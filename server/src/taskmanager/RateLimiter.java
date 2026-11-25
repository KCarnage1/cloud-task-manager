package taskmanager;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Allows at most `limit` hits per key inside a sliding window. */
public final class RateLimiter {
    private final int limit;
    private final long windowMillis;
    private final LongSupplier clock;
    private final Map<String, Deque<Long>> hits = new HashMap<>();

    public RateLimiter(int limit, long windowMillis, LongSupplier clock) {
        this.limit = limit; this.windowMillis = windowMillis; this.clock = clock;
    }

    /** Records an attempt. Returns 0 if allowed, otherwise the seconds to wait. */
    public synchronized long hit(String key) {
        long now = clock.getAsLong();
        Deque<Long> q = hits.computeIfAbsent(key, k -> new ArrayDeque<>());
        while (!q.isEmpty() && now - q.peekFirst() >= windowMillis) q.pollFirst();
        if (q.size() >= limit) return Math.max(1, (q.peekFirst() + windowMillis - now + 999) / 1000);
        q.addLast(now);
        if (hits.size() > 10_000) hits.values().removeIf(d -> d.isEmpty() || now - d.peekLast() >= windowMillis);
        return 0;
    }

    public synchronized void clear(String key) { hits.remove(key); }
}
