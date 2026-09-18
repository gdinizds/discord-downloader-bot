package dev.gdiniz.discorddownloaderbot.dto;

public record DownloadRequest(
        String correlationId,
        String guildId,
        String channelId,
        String userId,
        String interactionToken,
        String messageId,
        String url,
        String qualidade
) {}
