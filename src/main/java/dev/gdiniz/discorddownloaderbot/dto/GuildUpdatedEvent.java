package dev.gdiniz.discorddownloaderbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GuildUpdatedEvent(
        GuildInfo guild,
        RawPayload rawPayload
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuildInfo(String id) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawPayload(String field, TierInfo tier) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TierInfo(
            int level,
            int boostCount,
            long maxFileSizeBytes,
            int maxBitrateKbps
    ) {}

    public String guildId() {
        return guild != null ? guild.id() : null;
    }
}
