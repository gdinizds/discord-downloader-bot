package dev.gdiniz.discorddownloaderbot.kafka;

import tools.jackson.databind.ObjectMapper;
import dev.gdiniz.discorddownloaderbot.config.TopicsProperties;
import dev.gdiniz.discorddownloaderbot.dto.GatewayResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class ResponseProducer {

    private static final Logger log = LoggerFactory.getLogger(ResponseProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TopicsProperties topics;
    private final ObjectMapper objectMapper;

    public ResponseProducer(KafkaTemplate<String, String> kafkaTemplate,
                             TopicsProperties topics, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.topics = topics;
        this.objectMapper = objectMapper;
    }

    public void send(GatewayResponse response) {
        try {
            var json = objectMapper.writeValueAsString(response);
            kafkaTemplate.send(topics.outboundResponses(), response.correlationId(), json);
            log.debug("Sent response: correlationId={} type={}", response.correlationId(), response.responseType());
        } catch (Exception e) {
            log.error("Failed to send response: correlationId={}", response.correlationId(), e);
        }
    }

    public void sendRaw(String topic, String key, String payload) {
        kafkaTemplate.send(topic, key, payload);
    }
}
