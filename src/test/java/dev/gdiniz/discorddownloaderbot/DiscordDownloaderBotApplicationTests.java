package dev.gdiniz.discorddownloaderbot;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.kafka.test.context.EmbeddedKafka;
import software.amazon.awssdk.services.s3.S3Client;

@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {
        "discord.events.interaction.command",
        "discord.events.message.command",
        "discord.gateway.responses",
        "discord.gateway.commands"
})
class DiscordDownloaderBotApplicationTests {

    @MockitoBean
    S3Client s3Client;

    @Test
    void contextLoads() {
    }
}
