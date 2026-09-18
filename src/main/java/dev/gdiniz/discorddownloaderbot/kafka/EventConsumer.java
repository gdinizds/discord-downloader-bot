package dev.gdiniz.discorddownloaderbot.kafka;

import tools.jackson.databind.ObjectMapper;
import dev.gdiniz.discorddownloaderbot.dto.DiscordEventPayload;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import dev.gdiniz.discorddownloaderbot.service.DownloadOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);
    private static final Executor EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final DownloadOrchestrator orchestrator;
    private final ObjectMapper objectMapper;

    public EventConsumer(DownloadOrchestrator orchestrator, ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${topics.inbound-interactions}",
            groupId = "discord-downloader",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeInteraction(
            @Payload String payload,
            @Header(name = "command-name", required = false) String commandName) {
        if (!"download".equals(commandName)) return;

        try {
            var event = objectMapper.readValue(payload, DiscordEventPayload.class);
            var url = event.extractUrl();
            if (url == null || url.isBlank()) {
                log.warn("Missing URL in interaction event: correlationId={}", event.correlationId());
                return;
            }
            var request = new DownloadRequest(
                    event.correlationId(),
                    event.guildId(),
                    event.channelId(),
                    event.userId(),
                    event.interactionToken(),
                    event.messageId(),
                    url,
                    event.extractQualidade()
            );
            EXECUTOR.execute(() -> orchestrator.process(request));
        } catch (Exception e) {
            log.error("Failed to process interaction event", e);
        }
    }

    @KafkaListener(
            topics = "${topics.inbound-commands}",
            groupId = "discord-downloader",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consumeMessageCommand(
            @Payload String payload,
            @Header(name = "command-name", required = false) String commandName) {
        if (!"baixar".equals(commandName)) return;

        try {
            var event = objectMapper.readValue(payload, DiscordEventPayload.class);
            var url = event.extractUrl();
            if (url == null || url.isBlank()) {
                log.warn("Missing URL in message command: correlationId={}", event.correlationId());
                return;
            }
            var request = new DownloadRequest(
                    event.correlationId(),
                    event.guildId(),
                    event.channelId(),
                    event.userId(),
                    event.interactionToken(),
                    event.messageId(),
                    url,
                    "original"
            );
            EXECUTOR.execute(() -> orchestrator.process(request));
        } catch (Exception e) {
            log.error("Failed to process message command event", e);
        }
    }
}
