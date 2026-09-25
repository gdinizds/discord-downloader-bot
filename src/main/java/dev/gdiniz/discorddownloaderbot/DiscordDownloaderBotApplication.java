package dev.gdiniz.discorddownloaderbot;

import dev.gdiniz.discorddownloaderbot.domain.DownloadJob;
import dev.gdiniz.discorddownloaderbot.domain.DownloadSource;
import dev.gdiniz.discorddownloaderbot.domain.DownloadStatus;
import dev.gdiniz.discorddownloaderbot.domain.GuildConfig;
import dev.gdiniz.discorddownloaderbot.dto.DiscordEventPayload;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import dev.gdiniz.discorddownloaderbot.dto.DownloadResult;
import dev.gdiniz.discorddownloaderbot.dto.GatewayResponse;
import dev.gdiniz.discorddownloaderbot.dto.GuildUpdatedEvent;
import dev.gdiniz.discorddownloaderbot.dto.VideoProbe;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
@RegisterReflectionForBinding({
        DiscordEventPayload.class,
        DiscordEventPayload.GuildInfo.class,
        DiscordEventPayload.UserInfo.class,
        DiscordEventPayload.RawPayload.class,
        GatewayResponse.class,
        GatewayResponse.Embed.class,
        GatewayResponse.Footer.class,
        GatewayResponse.Attachment.class,
        GuildUpdatedEvent.class,
        GuildUpdatedEvent.GuildInfo.class,
        GuildUpdatedEvent.RawPayload.class,
        GuildUpdatedEvent.TierInfo.class,
        VideoProbe.class,
        DownloadRequest.class,
        DownloadResult.class,
        DownloadException.class,
        DownloadJob.class,
        DownloadSource.class,
        GuildConfig.class,
        DownloadStatus.class
})
public class DiscordDownloaderBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(DiscordDownloaderBotApplication.class, args);
    }
}
