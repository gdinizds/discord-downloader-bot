package dev.gdiniz.discorddownloaderbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("topics")
public record TopicsProperties(
        String inboundInteractions,
        String inboundCommands,
        String outboundResponses,
        String gatewayCommands,
        String guildUpdated
) {}
