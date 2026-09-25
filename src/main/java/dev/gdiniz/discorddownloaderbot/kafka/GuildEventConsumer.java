package dev.gdiniz.discorddownloaderbot.kafka;

import tools.jackson.databind.ObjectMapper;
import dev.gdiniz.discorddownloaderbot.domain.GuildConfig;
import dev.gdiniz.discorddownloaderbot.domain.GuildConfigRepository;
import dev.gdiniz.discorddownloaderbot.dto.GuildUpdatedEvent;
import dev.gdiniz.discorddownloaderbot.service.GuildConfigCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
public class GuildEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(GuildEventConsumer.class);

    private final GuildConfigRepository guildConfigRepository;
    private final GuildConfigCache guildConfigCache;
    private final ObjectMapper objectMapper;

    public GuildEventConsumer(GuildConfigRepository guildConfigRepository,
                              GuildConfigCache guildConfigCache,
                              ObjectMapper objectMapper) {
        this.guildConfigRepository = guildConfigRepository;
        this.guildConfigCache = guildConfigCache;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${topics.guild-updated}",
            groupId = "discord-downloader",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeGuildUpdated(@Payload String payload) {
        try {
            var event = objectMapper.readValue(payload, GuildUpdatedEvent.class);

            var guildId = event.guildId();
            if (guildId == null) {
                log.warn("Received guild updated event without guildId — skipping");
                return;
            }

            var raw = event.rawPayload();
            if (raw == null || !"boostTier".equals(raw.field()) || raw.tier() == null) return;

            var tier = raw.tier();
            var config = GuildConfig.from(guildId, tier.level(), tier.maxFileSizeBytes(), tier.maxBitrateKbps());
            guildConfigRepository.save(config);
            guildConfigCache.put(guildId, config);

            log.info("Guild config updated: guildId={} boostTier={} maxFileSizeMB={} maxBitrateKbps={}",
                    guildId, tier.level(), tier.maxFileSizeBytes() / 1_048_576, tier.maxBitrateKbps());

        } catch (Exception e) {
            log.error("Failed to process guild updated event", e);
        }
    }
}
