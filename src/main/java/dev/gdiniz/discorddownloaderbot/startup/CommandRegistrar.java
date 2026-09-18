package dev.gdiniz.discorddownloaderbot.startup;

import dev.gdiniz.discorddownloaderbot.config.TopicsProperties;
import dev.gdiniz.discorddownloaderbot.kafka.ResponseProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class CommandRegistrar implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CommandRegistrar.class);

    private final ResponseProducer responseProducer;
    private final TopicsProperties topics;

    public CommandRegistrar(ResponseProducer responseProducer, TopicsProperties topics) {
        this.responseProducer = responseProducer;
        this.topics = topics;
    }

    @Override
    public void run(ApplicationArguments args) {
        registerSlashCommand();
        registerDotCommand();
        log.info("Discord commands registered");
    }

    private void registerSlashCommand() {
        var payload = """
                {
                  "prefix": "SLASH",
                  "name": "download",
                  "description": "Baixa um vídeo ou imagem de um link",
                  "parameters": [
                    { "name": "link",      "description": "URL do conteúdo",  "type": "STRING", "required": true  },
                    { "name": "qualidade", "description": "Qualidade do vídeo (padrão: original, máx 1080p)", "type": "STRING", "required": false }
                  ],
                  "is_deleted": false
                }
                """;
        responseProducer.sendRaw(topics.gatewayCommands(), "download", payload);
    }

    private void registerDotCommand() {
        var payload = """
                {
                  "prefix": "DOT",
                  "name": "baixar",
                  "description": "Baixa um vídeo ou imagem — uso: .baixar <url>",
                  "is_deleted": false
                }
                """;
        responseProducer.sendRaw(topics.gatewayCommands(), "baixar", payload);
    }
}
