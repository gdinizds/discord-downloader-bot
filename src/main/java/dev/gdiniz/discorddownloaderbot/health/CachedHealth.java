package dev.gdiniz.discorddownloaderbot.health;

import org.springframework.boot.health.contributor.Health;

import java.time.Duration;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

final class CachedHealth {

    private final long ttlMillis;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Health cached;
    private volatile long checkedAt;

    CachedHealth(Duration ttl) {
        this.ttlMillis = ttl.toMillis();
    }

    Health get(Supplier<Health> check) {
        long now = System.currentTimeMillis();
        Health current = cached;
        if (current != null && (now - checkedAt) < ttlMillis) {
            return current;
        }

        if (lock.tryLock()) {
            try {
                if (cached != null && (System.currentTimeMillis() - checkedAt) < ttlMillis) {
                    return cached;
                }
                cached = check.get();
                checkedAt = System.currentTimeMillis();
                return cached;
            } finally {
                lock.unlock();
            }
        }

        if (current != null) {
            return current;
        }

        lock.lock();
        try {
            if (cached != null && (System.currentTimeMillis() - checkedAt) < ttlMillis) {
                return cached;
            }
            cached = check.get();
            checkedAt = System.currentTimeMillis();
            return cached;
        } finally {
            lock.unlock();
        }
    }
}
