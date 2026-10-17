package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.domain.GuildConfig;
import dev.gdiniz.discorddownloaderbot.domain.GuildConfigRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class GuildConfigCache {

    private static final int MAX_CACHE_ENTRIES = 10_000;

    private final GuildConfigRepository guildConfigRepository;
    private final Map<String, Optional<GuildConfig>> cache = new ConcurrentHashMap<>();

    public GuildConfigCache(GuildConfigRepository guildConfigRepository) {
        this.guildConfigRepository = guildConfigRepository;
    }

    public Optional<GuildConfig> get(String guildId) {
        if (guildId == null) {
            return Optional.empty();
        }
        Optional<GuildConfig> cached = cache.get(guildId);
        if (cached != null) {
            return cached;
        }
        Optional<GuildConfig> loaded = guildConfigRepository.findById(guildId);
        if (cache.size() >= MAX_CACHE_ENTRIES) {
            cache.clear();
        }
        cache.put(guildId, loaded);
        return loaded;
    }

    public void put(String guildId, GuildConfig config) {
        if (guildId != null) {
            if (cache.size() >= MAX_CACHE_ENTRIES) {
                cache.clear();
            }
            cache.put(guildId, Optional.ofNullable(config));
        }
    }

    public void invalidate(String guildId) {
        if (guildId != null) {
            cache.remove(guildId);
        }
    }
}
