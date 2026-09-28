package dev.gdiniz.discorddownloaderbot.kafka;

import tools.jackson.databind.ObjectMapper;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import dev.gdiniz.discorddownloaderbot.service.DownloadOrchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class EventConsumerTest {

    @Mock
    private DownloadOrchestrator orchestrator;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private EventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new EventConsumer(orchestrator, objectMapper, Runnable::run);
    }

    @Test
    void consumeInteraction_withDownloadCommand_dispatchesRequest() throws Exception {
        var payload = """
                {
                  "eventType": "INTERACTION_COMMAND",
                  "correlationId": "abc-123",
                  "guild": { "id": "g1", "name": "Server" },
                  "channelId": "ch1",
                  "user": { "id": "u1", "username": "user" },
                  "interactionToken": "token-abc",
                  "rawPayload": {
                    "args": { "link": "https://youtube.com/watch?v=xyz", "qualidade": "720p" }
                  }
                }
                """;

        consumer.consumeInteraction(payload, "download");

        var captor = ArgumentCaptor.forClass(DownloadRequest.class);
        verify(orchestrator).process(captor.capture());

        var request = captor.getValue();
        assertThat(request.correlationId()).isEqualTo("abc-123");
        assertThat(request.url()).isEqualTo("https://youtube.com/watch?v=xyz");
        assertThat(request.qualidade()).isEqualTo("720p");
        assertThat(request.interactionToken()).isEqualTo("token-abc");
    }

    @Test
    void consumeInteraction_withWrongCommandName_doesNotDispatch() {
        consumer.consumeInteraction("{}", "other");

        verifyNoInteractions(orchestrator);
    }

    @Test
    void consumeMessageCommand_withBaixarCommand_dispatchesRequest() throws Exception {
        var payload = """
                {
                  "eventType": "MESSAGE_COMMAND",
                  "correlationId": "msg-456",
                  "rawPayload": {
                    "args": ["https://tiktok.com/@user/video/123"]
                  }
                }
                """;

        consumer.consumeMessageCommand(payload, "baixar");

        var captor = ArgumentCaptor.forClass(DownloadRequest.class);
        verify(orchestrator).process(captor.capture());

        var request = captor.getValue();
        assertThat(request.correlationId()).isEqualTo("msg-456");
        assertThat(request.url()).isEqualTo("https://tiktok.com/@user/video/123");
        assertThat(request.qualidade()).isEqualTo("original");
    }

    @Test
    void consumeMessageCommand_stripsDiscordLinkFormatting() {
        var payload = """
                {"correlationId": "msg-789", "rawPayload": {"args": ["<https://youtube.com/watch?v=abc>"]}}
                """;

        consumer.consumeMessageCommand(payload, "baixar");

        var captor = ArgumentCaptor.forClass(DownloadRequest.class);
        verify(orchestrator).process(captor.capture());
        assertThat(captor.getValue().url()).isEqualTo("https://youtube.com/watch?v=abc");
    }

    @Test
    void consumeInteraction_normalizesQuality() {
        var payload = """
                {"correlationId": "q-1", "interactionToken": "t",
                 "rawPayload": {"args": {"link": "https://youtube.com/watch?v=x", "qualidade": " 720 "}}}
                """;

        consumer.consumeInteraction(payload, "download");

        var captor = ArgumentCaptor.forClass(DownloadRequest.class);
        verify(orchestrator).process(captor.capture());
        assertThat(captor.getValue().qualidade()).isEqualTo("720p");
    }

    @Test
    void consumeInteraction_withNonTextLink_isIgnored() {
        var payload = """
                {"correlationId": "bad-1", "rawPayload": {"args": {"link": 123}}}
                """;

        consumer.consumeInteraction(payload, "download");

        verifyNoInteractions(orchestrator);
    }

    @Test
    void qualityValuesOutsideTheSupportedSetFallBackToOriginal() {
        assertThat(EventConsumer.normalizeQualidade("1080P")).isEqualTo("1080p");
        assertThat(EventConsumer.normalizeQualidade("360")).isEqualTo("360p");
        assertThat(EventConsumer.normalizeQualidade("4k")).isEqualTo("original");
        assertThat(EventConsumer.normalizeQualidade(null)).isEqualTo("original");
        assertThat(EventConsumer.normalizeQualidade("original")).isEqualTo("original");
    }
}
