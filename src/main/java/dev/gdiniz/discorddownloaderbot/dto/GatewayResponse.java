package dev.gdiniz.discorddownloaderbot.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record GatewayResponse(
        String responseType,
        String interactionToken,
        String messageId,
        String channelId,
        String correlationId,
        String content,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<Embed> embeds,
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        List<Attachment> attachments,
        Boolean finished
    ) {
        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Embed(String title, String description, Integer color, Footer footer) {}

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Footer(String text) {}

        @JsonInclude(JsonInclude.Include.NON_NULL)
        public record Attachment(String url, String name, String description) {}

        public static GatewayResponse deferredReply(String interactionToken, String correlationId,
                                                     String content, List<Embed> embeds, List<Attachment> attachments, Boolean finished) {
            return new GatewayResponse("DEFERRED_REPLY", interactionToken, null, null, correlationId, content, embeds, attachments, finished);
        }

        public static GatewayResponse ephemeralReply(String interactionToken, String correlationId, String content, Boolean finished) {
            return new GatewayResponse("EPHEMERAL_REPLY", interactionToken, null, null, correlationId, content, null, null, finished);
        }

        public static GatewayResponse messageReply(String messageId, String channelId, String correlationId,
                                                    String content, List<Embed> embeds, List<Attachment> attachments, Boolean finished) {
            return new GatewayResponse("REPLY", null, messageId, channelId, correlationId, content, embeds, attachments, finished);
        }

        public static GatewayResponse updateMessage(String messageId, String channelId, String correlationId,
                                                     String content, List<Embed> embeds, List<Attachment> attachments, Boolean finished) {
            return new GatewayResponse("UPDATE_MESSAGE", null, messageId, channelId, correlationId, content, embeds, attachments, finished);
        }
}
