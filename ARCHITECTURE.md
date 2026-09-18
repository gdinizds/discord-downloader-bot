# discord-downloader-bot — Tecnologias e Estrutura

Microserviço que baixa vídeos/imagens de links (YouTube, TikTok, Instagram, X/Twitter etc.) recebidos via Discord, processa (transcodifica/comprime se necessário) e sobe pra um bucket S3-compatível. Não fala diretamente com a Discord Gateway API — é orientado a eventos via Kafka, consumindo comandos de um serviço "gateway" separado e publicando respostas de volta.

---

## 1. Stack de tecnologias

### Linguagem / Runtime
- **Java 25** (LTS), com **Virtual Threads** habilitadas (`spring.threads.virtual.enabled`) — cada Kafka listener e request roda em thread virtual.
- **Gradle** (Groovy DSL) como build tool, wrapper incluso (`gradlew`).

### Framework
- **Spring Boot 4.0.6** (Spring Framework 7.x transitivo)
  - `spring-boot-starter-web` — API REST (Tomcat embarcado) + Virtual Threads
  - `spring-boot-starter-data-jpa` — persistência (Hibernate ORM 7)
  - `spring-boot-starter-actuator` — health/metrics/endpoints operacionais
  - `spring-boot-kafka` / `spring-kafka` — consumo/produção de eventos Kafka
  - `spring-boot-flyway` — migrações de banco versionadas
  - `aspectjweaver` — suporte a AOP (usado pelas anotações do Resilience4j)

### Banco de dados
- **PostgreSQL** (driver `org.postgresql:postgresql`)
- **Flyway** para migrações (`src/main/resources/db/migration`)
- **Spring Data JPA** / Hibernate para acesso a dados

### Mensageria
- **Kafka** (via `spring-kafka`), broker real é **Redpanda** (confirmado pelo cluster ID nos logs)
- Tópicos consumidos: interações de slash command, comandos por mensagem, atualização de guild
- Tópicos publicados: respostas/gateway commands de volta pro serviço de gateway do Discord

### Download / processamento de mídia
- **yt-dlp** — extração/download de vídeo, invocado via `ProcessBuilder` (não é lib Java, é o binário Python instalado na imagem)
  - extra `curl-cffi` para impersonation de browser (necessário pro extractor do TikTok)
- **ffmpeg** — transcodificação/compressão, também via `ProcessBuilder`, com encode via CPU (`libx264`)
- **deno** — instalado na imagem, usado pelo yt-dlp para resolver desafios JS de alguns extractors

### Storage de objetos
- **AWS SDK v2 (`software.amazon.awssdk:s3`)** apontando pra um endpoint **S3-compatível** (Garage, conforme propriedades `downloader.s3.*` / `GARAGE_*`)

### Resiliência
- **Resilience4j 2.2.0** (`resilience4j-spring-boot3`, `resilience4j-micrometer`)
  - Circuit Breaker, Retry e Bulkhead aplicados via anotações (`@CircuitBreaker`, `@Retry`, `@Bulkhead`) nos serviços de yt-dlp, ffmpeg e upload S3

### Observabilidade
- **Micrometer** + `micrometer-registry-prometheus` — métricas expostas em `/actuator/prometheus` (ver `METRICS.md`)
- **OpenTelemetry** (`micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`) — tracing distribuído, exportado via OTLP
- **Actuator Health Indicators customizados** — checam yt-dlp, ffmpeg e S3 (`HeadBucket`)

### Testes
- **JUnit 5** (Jupiter)
- **Mockito** (`mockito-junit-jupiter`) — mocks/spies
- **AssertJ** — assertions fluentes
- **Spring Boot Test** (`@SpringBootTest`, `@MockitoBean`)
- **spring-kafka-test** (`@EmbeddedKafka`) — testes de integração com Kafka em memória
- **H2** — banco em memória para testes (runtime only)

### Infra / Deploy
- **Docker** multi-stage build (`eclipse-temurin:25-jdk-noble` para build, `eclipse-temurin:25-jre-noble` para runtime)
- **Kubernetes** (manifests em `k8s/`) — namespace, deployment, service, configmap, secret

---

## 2. Estrutura de pastas

```
discord-downloader-bot/
├── Dockerfile                      # build multi-stage (JDK → JRE + yt-dlp + ffmpeg + deno)
├── entrypoint.sh                   # atualiza yt-dlp no boot, copia cookies, sobe a JVM
├── build.gradle                    # dependências e plugins
├── settings.gradle                 # nome do projeto raiz
├── gradlew / gradlew.bat           # wrapper do Gradle
├── HELP.md                         # ajuda padrão gerada pelo Spring Initializr
├── discord-downloader-bot.md       # spec técnica do projeto
├── METRICS.md                      # documentação das métricas expostas
├── ARCHITECTURE.md                 # este arquivo
│
├── k8s/                            # manifests Kubernetes
│   ├── namespace.yaml
│   ├── deployment.yaml             # Deployment (2 réplicas, probes, volumes)
│   ├── service.yaml
│   ├── configmap.yaml              # env vars não sensíveis
│   └── secret.yaml                 # secrets (DB, S3, cookies do yt-dlp, etc.)
│
└── src/
    ├── main/
    │   ├── java/dev/gdiniz/discorddownloaderbot/
    │   │   ├── DiscordDownloaderBotApplication.java   # classe main (@SpringBootApplication)
    │   │   │
    │   │   ├── config/                                 # beans de configuração e @ConfigurationProperties
    │   │   │   ├── DownloaderProperties.java            # paths (yt-dlp/ffmpeg), limites, timeouts, S3
    │   │   │   ├── KafkaConfig.java                     # configuração dos listeners/producers Kafka
    │   │   │   ├── S3Config.java                        # bean do S3Client (AWS SDK v2)
    │   │   │   └── TopicsProperties.java                # nomes dos tópicos Kafka (bind do application.yaml)
    │   │   │
    │   │   ├── domain/                                  # entidades JPA + repositórios
    │   │   │   ├── DownloadJob.java                      # entidade: job de download (status, tamanho, s3Key…)
    │   │   │   ├── DownloadJobRepository.java
    │   │   │   ├── DownloadSource.java                   # entidade: config por host (formato, extra_args)
    │   │   │   ├── DownloadSourceRepository.java
    │   │   │   ├── DownloadStatus.java                   # enum de status do job
    │   │   │   ├── GuildConfig.java                      # entidade: config por servidor Discord (limite de arquivo)
    │   │   │   └── GuildConfigRepository.java
    │   │   │
    │   │   ├── dto/                                      # objetos de transporte (não persistidos)
    │   │   │   ├── DiscordEventPayload.java               # payload cru vindo do Kafka
    │   │   │   ├── DownloadException.java                 # exceção de domínio
    │   │   │   ├── DownloadRequest.java                   # request normalizado de download
    │   │   │   ├── DownloadResult.java                    # resultado do yt-dlp (arquivo, título, tamanho)
    │   │   │   ├── GatewayResponse.java                   # payload de resposta pro serviço de gateway
    │   │   │   ├── GuildUpdatedEvent.java                  # evento de atualização de guild
    │   │   │   └── VideoProbe.java                         # resultado do probe (tamanho/duração estimados)
    │   │   │
    │   │   ├── health/                                    # HealthIndicators customizados do Actuator
    │   │   │   ├── FfmpegHealthIndicator.java              # `ffmpeg -version`
    │   │   │   ├── S3HealthIndicator.java                  # HeadBucket no S3/Garage
    │   │   │   └── YtDlpHealthIndicator.java               # `yt-dlp --version`
    │   │   │
    │   │   ├── kafka/                                     # consumers/producers Kafka
    │   │   │   ├── EventConsumer.java                      # consome interações/comandos → dispara download
    │   │   │   ├── GuildEventConsumer.java                 # consome eventos de guild.updated
    │   │   │   └── ResponseProducer.java                   # publica respostas pro gateway
    │   │   │
    │   │   ├── service/                                    # regras de negócio / integrações externas
    │   │   │   ├── DownloadOrchestrator.java                # orquestra o fluxo completo do download
    │   │   │   ├── FfmpegService.java                       # transcodificação/compressão (CPU, libx264)
    │   │   │   ├── S3UploadService.java                     # upload pro S3/Garage
    │   │   │   ├── YtDlpService.java                        # execução do yt-dlp (probe + download)
    │   │   │   └── YtDlpUpdater.java                        # atualização agendada do yt-dlp (cron)
    │   │   │
    │   │   └── startup/
    │   │       └── CommandRegistrar.java                    # registra slash command e comando por texto no boot
    │   │
    │   └── resources/
    │       ├── application.yaml                             # config central (DB, Kafka, S3, Resilience4j, Actuator)
    │       └── db/migration/                                 # migrações Flyway
    │           ├── V1__init.sql
    │           ├── V2__add_tiktok_variants.sql
    │           ├── V3__guild_configs.sql
    │           ├── V4__ytdlp_cookies_ig_tiktok.sql
    │           └── V5__ytdlp_cookies_youtube.sql
    │
    └── test/
        ├── java/dev/gdiniz/discorddownloaderbot/
        │   ├── DiscordDownloaderBotApplicationTests.java    # smoke test de contexto Spring
        │   ├── kafka/
        │   │   └── EventConsumerTest.java                    # teste de integração com @EmbeddedKafka
        │   └── service/
        │       ├── FfmpegServiceTest.java                    # unit test (Mockito)
        │       ├── S3UploadServiceTest.java                  # unit test (Mockito + AWS SDK mocks)
        │       └── YtDlpServiceTest.java                     # unit test (Mockito)
        │
        └── resources/
            └── application.yaml                              # config de teste (H2, overrides)
```

---

## 3. Notas de arquitetura

- **Sem SDK do Discord no classpath**: o bot não conversa diretamente com a Gateway/REST API do Discord. Ele recebe comandos/interações via Kafka (produzidos por um serviço de gateway externo) e publica respostas de volta pelo mesmo canal (`ResponseProducer` → `topics.outbound-responses` / `topics.gateway-commands`).
- **`DownloadOrchestrator`** é o coração do fluxo: valida tamanho via probe → decide se precisa comprimir antes de baixar → baixa (`YtDlpService`) → transcodifica se necessário (`FfmpegService`) → verifica tamanho final e recomprime se preciso → sobe pro S3 (`S3UploadService`) → responde no Discord → registra métricas.
- **`DownloadSource`** (tabela `download_sources`) guarda, por host, o seletor de formato do yt-dlp e `extra_args` (ex.: `--cookies /app/cookies/cookies.txt` para YouTube/Instagram/TikTok) — permite configurar por host sem precisar de deploy.
