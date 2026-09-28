package dev.gdiniz.discorddownloaderbot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DiscordEventPayload(
        String eventType,
        String correlationId,
        String priority,
        GuildInfo guild,
        String channelId,
        UserInfo user,
        String interactionToken,
        String messageId,
        Integer version,
        List<Object> attachments,
        RawPayload rawPayload
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuildInfo(String id, String name, String iconUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserInfo(String id, String username, String avatarUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RawPayload(Object args) {}

    public String extractUrl() {
        if (rawPayload == null || rawPayload.args() == null) return null;
        return switch (rawPayload.args()) {
            case Map<?, ?> map -> map.get("link") instanceof String link ? link : null;
            case List<?> list when !list.isEmpty() -> list.getFirst() instanceof String first ? first : null;
            default -> null;
        };
    }

    public String extractQualidade() {
        if (rawPayload == null || rawPayload.args() == null) return "original";
        return switch (rawPayload.args()) {
            case Map<?, ?> map -> map.get("qualidade") instanceof String q ? q : "original";
            default -> "original";
        };
    }

    public String guildId() {
        return guild != null ? guild.id() : null;
    }

    public String userId() {
        return user != null ? user.id() : null;
    }
}
