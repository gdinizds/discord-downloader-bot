package dev.gdiniz.discorddownloaderbot.health;

import org.springframework.boot.health.contributor.Health;

import java.time.Duration;
import java.util.function.Supplier;

final class CachedHealth {

    private final long ttlMillis;
    private volatile Health cached;
    private volatile long checkedAt;

    CachedHealth(Duration ttl) {
        this.ttlMillis = ttl.toMillis();
    }

    Health get(Supplier<Health> check) {
        long now = System.currentTimeMillis();
        var current = cached;
        if (current != null && now - checkedAt < ttlMillis) return current;
        synchronized (this) {
            if (cached != null && System.currentTimeMillis() - checkedAt < ttlMillis) return cached;
            cached = check.get();
            checkedAt = System.currentTimeMillis();
            return cached;
        }
    }
}
