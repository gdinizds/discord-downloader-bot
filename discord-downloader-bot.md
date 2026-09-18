# discord-downloader-bot — Spec de Projeto

## Stack Técnica

| Camada | Escolha | Versão |
|---|---|---|
| Java | Java 25 (LTS) | 25 |
| Framework | Spring Boot | 4.0.6 |
| Spring Framework | (transitivo via Boot) | 7.0.x |
| Jakarta EE | Baseline obrigatório Boot 4 | EE 11 |
| JSON | Jackson 3 (padrão Boot 4) | 3.x |
| Concorrência | Spring MVC + Virtual Threads (Project Loom) | — |
| Kafka | spring-kafka | 4.1.x |
| Banco | Spring Data JPA + Flyway | — |
| S3 | AWS SDK v2 | 2.x |
| Download | `yt-dlp` via `ProcessBuilder` | — |
| Encode | `ffmpeg` via `ProcessBuilder` (NVENC) | — |
| Resiliência | Resilience4j | 2.x |
| Observabilidade | Micrometer + OpenTelemetry + Actuator | — |
| Build | **Gradle (Kotlin DSL)** | 9.x |

### Virtual Threads — configuração Spring MVC

Spring Boot 4 com Spring MVC usa Virtual Threads para tornar o modelo thread-per-request não bloqueante sem WebFlux. Cada request (e cada Kafka listener) roda numa Virtual Thread gerenciada pela JVM, liberando o carrier thread durante I/O.

Configurar no `application.yml`:
```yaml
spring:
  threads:
    virtual:
      enabled: true
```

Isso habilita automaticamente:
- `TomcatVirtualThreadsProtocolHandlerCustomizer` — Tomcat usa Virtual Threads para requests HTTP
- `SimpleAsyncTaskExecutorBuilder` com `virtualThreads: true` — executores de tarefas assíncronas
- Kafka listeners executam em Virtual Threads via `VirtualThreadTaskExecutor`

**Não usar R2DBC nem WebFlux** — Virtual Threads no MVC síncrono é o modelo correto aqui. Download é I/O bound (yt-dlp, ffmpeg, S3) e o modelo reativo adicionaria complexidade sem benefício.

### Anotações Jakarta EE 11

Spring Boot 4 / Spring Framework 7 usa namespace `jakarta.*` em toda a stack:
- `jakarta.persistence.*` (JPA 3.2)
- `jakarta.validation.*` (Bean Validation 3.1)
- `jakarta.servlet.*` (Servlet 6.1)

**Nunca usar `javax.*`** — removido completamente no Boot 4.

### Jackson 3

Jackson 3 é o padrão no Boot 4. Pacote migrou para `tools.jackson.*`. Se usar anotações de serialização customizadas nos records, verificar compatibilidade com Jackson 3.

---

## Visão Geral

Bot Discord que recebe um link via slash command ou comando de texto e baixa o conteúdo (vídeo ou imagem), faz encode com GPU (NVENC) quando necessário e armazena no S3 (Garage), respondendo ao usuário com o arquivo ou link.

---

## Infraestrutura

| Recurso | Endereço |
|---|---|
| Redpanda (Kafka) | `192.168.10.95:9092` |
| PostgreSQL | `192.168.10.94`, database `discordDownloaderdb` |
| S3 / Garage | `http://192.168.10.96:3900`, bucket `discord-attachments` |
| GPU (NVENC) | Quadro RTX 5000 — disponível via passthrough LXD no node MicroK8s |

### Usuários PostgreSQL

| Usuário | Finalidade |
|---|---|
| `discord_downloader_flyway` | Migrations (DDL, schema owner) |
| `discord_downloader_api` | Runtime da aplicação (DML apenas) |

### Bucket S3

- Nome: `discord-attachments`
- Key pattern: `<guild_id>/<correlationId>/<filename>`

---

## Comandos Suportados

### Slash command
```
/download link:<url> qualidade:<opcional>
```

Parâmetro `qualidade` (opcional, tipo STRING):
- Valores aceitos: `original`, `1080p`, `720p`, `480p`, `360p`
- Padrão: `original` — encode em h264, limitado a 1080p no máximo
- Se omitido, comporta-se como `original`

### Comando de texto (dot command)
```
.baixar <url>
```
- Sem parâmetro de qualidade — sempre usa `original`
- Deve ser registrado no gateway com `prefix: DOT`, `name: baixar`

---

## Contrato de Tópicos

O projeto consome e produz seguindo o contrato do `discord-event-gateway`.

### Inbound — `discord.events.interaction.command`
Header Kafka: `command-name: download`

```json
{
  "eventType": "INTERACTION_COMMAND",
  "correlationId": "uuid-v7",
  "priority": "normal",
  "guild": { "id": "string", "name": "string", "iconUrl": "string|null" },
  "channelId": "string",
  "user": { "id": "string", "username": "string", "avatarUrl": "string" },
  "interactionToken": "string",
  "messageId": null,
  "version": 1,
  "attachments": [],
  "rawPayload": {
    "args": {
      "link": "https://...",
      "qualidade": "1080p"
    }
  }
}
```

### Inbound — `discord.events.message.command`
Header Kafka: `command-name: baixar`

```json
{
  "eventType": "MESSAGE_COMMAND",
  "correlationId": "uuid-v7",
  "rawPayload": {
    "args": ["https://..."]
  }
}
```

### Outbound — `discord.gateway.responses`

Resposta de sucesso:
```json
{
  "responseType": "DEFERRED_REPLY",
  "interactionToken": "string",
  "correlationId": "string",
  "content": "✅ Download concluído!",
  "embeds": [
    {
      "title": "Nome do arquivo",
      "description": "Fonte: YouTube • Qualidade: 1080p • Tamanho: 12.4 MB",
      "color": 5763719,
      "footer": { "text": "discord-downloader-bot" }
    }
  ]
}
```

Resposta de erro — arquivo maior que o limite do Discord (25 MB):
```json
{
  "responseType": "DEFERRED_REPLY",
  "interactionToken": "string",
  "correlationId": "string",
  "content": "❌ O arquivo tem **48.3 MB** e excede o limite do Discord (25 MB). Tente uma qualidade menor com `/download link:<url> qualidade:480p`."
}
```

Resposta de erro — fonte não suportada ou falha no download:
```json
{
  "responseType": "EPHEMERAL_REPLY",
  "interactionToken": "string",
  "correlationId": "string",
  "content": "❌ Não foi possível baixar o conteúdo. Verifique se o link é válido e de uma fonte suportada."
}
```

---

## Registro de Comandos no Startup

Ao iniciar, publicar em `discord.gateway.commands` — **sem `bot_id`** (removido do contrato):

```json
{
  "guild_id": "${GUILD_ID}",
  "prefix": "SLASH",
  "name": "download",
  "description": "Baixa um vídeo ou imagem de um link",
  "parameters": [
    { "name": "link",      "description": "URL do conteúdo",  "type": "STRING", "required": true  },
    { "name": "qualidade", "description": "Qualidade do vídeo (padrão: original, máx 1080p)", "type": "STRING", "required": false }
  ],
  "is_deleted": false
}
```

```json
{
  "guild_id": "${GUILD_ID}",
  "prefix": "DOT",
  "name": "baixar",
  "description": "Baixa um vídeo ou imagem — uso: .baixar <url>",
  "is_deleted": false
}
```

---

## Banco de Dados — Schema (Flyway)

### `V1__init.sql`

#### Tabela `download_sources`

Parâmetros de download por fonte. A aplicação consulta esta tabela pelo host da URL antes de invocar o yt-dlp.

| Coluna | Tipo | Descrição |
|---|---|---|
| `id` | `BIGSERIAL PK` | |
| `host` | `VARCHAR(100) UNIQUE NOT NULL` | Ex: `youtube.com` |
| `format_selector` | `TEXT NOT NULL` | Seletor de formato yt-dlp |
| `extra_args` | `TEXT[]` | Args adicionais (ex: `--no-playlist`) |
| `requires_auth` | `BOOLEAN DEFAULT false` | Se precisa de cookies |
| `enabled` | `BOOLEAN DEFAULT true` | Liga/desliga a fonte |
| `created_at` | `TIMESTAMPTZ DEFAULT now()` | |

Dados iniciais:

| host | format_selector | extra_args |
|---|---|---|
| `youtube.com` | `bestvideo[ext=mp4][height<=1080]+bestaudio[ext=m4a]/best[ext=mp4]/best` | `{--merge-output-format,mp4,--no-playlist}` |
| `youtu.be` | (igual youtube.com) | (igual youtube.com) |
| `twitter.com` | `best[ext=mp4]/best` | `{--no-playlist}` |
| `x.com` | `best[ext=mp4]/best` | `{--no-playlist}` |
| `instagram.com` | `best[ext=mp4]/best` | `{--no-playlist,--no-check-certificates}` |
| `tiktok.com` | `best[ext=mp4]/best` | `{--no-playlist,--no-check-certificates}` |

#### Tabela `download_jobs`

Log de cada download processado.

| Coluna | Tipo |
|---|---|
| `id` | `BIGSERIAL PK` |
| `correlation_id` | `VARCHAR(36) NOT NULL` |
| `guild_id` | `VARCHAR(30) NOT NULL` |
| `user_id` | `VARCHAR(30) NOT NULL` |
| `url` | `TEXT NOT NULL` |
| `source_host` | `VARCHAR(100)` |
| `qualidade` | `VARCHAR(20)` |
| `status` | `VARCHAR(20) NOT NULL` — `PENDING`, `DOWNLOADING`, `ENCODING`, `UPLOADING`, `DONE`, `FAILED` |
| `file_size_bytes` | `BIGINT` |
| `s3_key` | `TEXT` |
| `error_message` | `TEXT` |
| `created_at` | `TIMESTAMPTZ DEFAULT now()` |
| `completed_at` | `TIMESTAMPTZ` |

---

## Resiliência e Circuit Breaker

Usar **Resilience4j** integrado via `resilience4j-spring-boot3`. Aplicar nos pontos de falha externos: yt-dlp, ffmpeg, S3 e PostgreSQL.

### Circuit Breakers definidos

| Nome | Protege | Threshold de abertura | Timeout de espera |
|---|---|---|---|
| `ytdlp` | execução do yt-dlp | 50% falhas em 10 calls | 30s |
| `ffmpeg` | execução do ffmpeg | 50% falhas em 10 calls | 30s |
| `s3Upload` | upload para Garage S3 | 50% falhas em 10 calls | 60s |
| `database` | consultas ao PostgreSQL | 50% falhas em 10 calls | 15s |

### Retry

Aplicar retry com backoff exponencial nos serviços externos:

| Nome | Max tentativas | Backoff inicial | Multiplicador |
|---|---|---|---|
| `ytdlpRetry` | 2 | 2s | 2x |
| `s3Retry` | 3 | 1s | 2x |
| `databaseRetry` | 3 | 500ms | 2x |

> **Não aplicar retry em ffmpeg** — se o encode falhou, nova tentativa com o mesmo arquivo vai falhar igual. Logar e enviar erro direto ao usuário.

### Bulkhead

Limitar concorrência de operações pesadas (yt-dlp e ffmpeg compartilham GPU/CPU):

| Nome | Max calls simultâneos | Max calls em espera |
|---|---|---|
| `ytdlpBulkhead` | 5 | 10 |
| `ffmpegBulkhead` | 3 | 5 |

### Fallback

Quando o circuit breaker estiver aberto ou todas as tentativas esgotadas:
- Publicar `EPHEMERAL_REPLY` informando que o serviço está indisponível temporariamente
- Atualizar `download_jobs.status = FAILED` com `error_message` descritivo
- Logar evento com `correlationId` para rastreabilidade

### Configuração (`application.yml`)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      ytdlp:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
      ffmpeg:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 30s
        permittedNumberOfCallsInHalfOpenState: 3
      s3Upload:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 60s
        permittedNumberOfCallsInHalfOpenState: 3
      database:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 15s
        permittedNumberOfCallsInHalfOpenState: 3

  retry:
    instances:
      ytdlpRetry:
        maxAttempts: 2
        waitDuration: 2s
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
      s3Retry:
        maxAttempts: 3
        waitDuration: 1s
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
      databaseRetry:
        maxAttempts: 3
        waitDuration: 500ms
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2

  bulkhead:
    instances:
      ytdlpBulkhead:
        maxConcurrentCalls: 5
        maxWaitDuration: 10s
      ffmpegBulkhead:
        maxConcurrentCalls: 3
        maxWaitDuration: 10s
```

---

## Observabilidade

### Camadas

```
Aplicação
  └── Micrometer (facade de métricas)
        ├── Prometheus (scrape /actuator/prometheus) → Grafana
        └── OpenTelemetry OTLP (traces + métricas) → Jaeger / Tempo
```

### Métricas customizadas (Micrometer)

Registrar as seguintes métricas além das automáticas do Spring Boot + Resilience4j:

| Métrica | Tipo | Tags |
|---|---|---|
| `downloader.job.started` | Counter | `source_host`, `qualidade` |
| `downloader.job.completed` | Counter | `source_host`, `qualidade` |
| `downloader.job.failed` | Counter | `source_host`, `qualidade`, `reason` |
| `downloader.job.duration` | Timer | `source_host`, `qualidade` |
| `downloader.file.size` | DistributionSummary | `source_host` |
| `downloader.ytdlp.duration` | Timer | `source_host` |
| `downloader.ffmpeg.duration` | Timer | — |
| `downloader.s3.upload.duration` | Timer | — |

### Tracing (OpenTelemetry)

Propagar `correlationId` do envelope Kafka como atributo de span:

```java
Span.current().setAttribute("discord.correlationId", correlationId);
Span.current().setAttribute("discord.guildId", guildId);
Span.current().setAttribute("download.sourceHost", sourceHost);
Span.current().setAttribute("download.qualidade", qualidade);
```

Criar spans explícitos para as operações pesadas:
- `ytdlp.execute` — duração total do download
- `ffmpeg.encode` — duração do encode
- `s3.upload` — duração do upload

### Actuator endpoints expostos

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics, circuitbreakers, retries
  endpoint:
    health:
      show-details: always
  health:
    circuitbreakers:
      enabled: true
    diskspace:
      enabled: true
  metrics:
    export:
      prometheus:
        enabled: true
  tracing:
    sampling:
      probability: 1.0   # 100% em dev; reduzir em prod
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}
```

### Health checks customizados

Implementar `HealthIndicator` para:

| Indicator | Verifica |
|---|---|
| `YtDlpHealthIndicator` | `yt-dlp --version` executável e retorna saída esperada |
| `FfmpegHealthIndicator` | `ffmpeg -version` executável |
| `GpuHealthIndicator` | `/dev/nvidia0` existe e é acessível |
| `S3HealthIndicator` | `HeadBucket` no bucket `discord-attachments` |

---

## Lógica de Download

### Fluxo

```
1. Consumir evento do Kafka (Virtual Thread)
2. Extrair URL e qualidade do rawPayload.args
3. Extrair host da URL → buscar download_sources no banco [circuit breaker: database]
4. Construir args do yt-dlp:
   - format_selector ajustado pela qualidade solicitada
   - extra_args da tabela
   - -o /tmp/discord-downloads/<correlationId>/%(title)s.%(ext)s
   - --max-filesize 200m (proteção)
5. Executar yt-dlp via ProcessBuilder [bulkhead: ytdlpBulkhead] [circuit breaker: ytdlp] [retry: ytdlpRetry]
6. Verificar tamanho do arquivo resultante
   - Se > 25MB → publicar mensagem de erro e encerrar
7. Se necessário re-encode: ffmpeg -c:v h264_nvenc [bulkhead: ffmpegBulkhead] [circuit breaker: ffmpeg]
8. Upload para S3 [circuit breaker: s3Upload] [retry: s3Retry]
9. Publicar resposta em discord.gateway.responses
10. Limpar /tmp/discord-downloads/<correlationId>/
11. Atualizar download_jobs com status e s3_key
```

> **Nota sobre Virtual Threads:** O `ProcessBuilder.waitFor()` e as operações de I/O com S3 bloqueiam a Virtual Thread, não o carrier thread do sistema. Isso significa que centenas de downloads podem rodar em paralelo sem esgotar a thread pool. Não é necessário nenhum código reativo — o modelo síncrono funciona corretamente.

### Mapeamento de Qualidade → format_selector yt-dlp

| qualidade | format_selector override |
|---|---|
| `original` | usa o da tabela (máx 1080p) |
| `1080p` | `bestvideo[height<=1080][ext=mp4]+bestaudio/best[height<=1080]` |
| `720p` | `bestvideo[height<=720][ext=mp4]+bestaudio/best[height<=720]` |
| `480p` | `bestvideo[height<=480][ext=mp4]+bestaudio/best[height<=480]` |
| `360p` | `bestvideo[height<=360][ext=mp4]+bestaudio/best[height<=360]` |

### Encode GPU (FFmpeg NVENC)

Aplicar quando o arquivo resultante não for h264 ou quando qualidade for explicitamente solicitada:

```bash
ffmpeg -i <input> \
  -c:v h264_nvenc \
  -preset fast \
  -cq 23 \
  -c:a aac \
  -movflags +faststart \
  <output>
```

---

## Configuração (`application.yml`)

```yaml
spring:
  threads:
    virtual:
      enabled: true

  kafka:
    bootstrap-servers: 192.168.10.95:9092
    consumer:
      group-id: discord-downloader
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer

  datasource:
    url: jdbc:postgresql://192.168.10.94:5432/discordDownloaderdb
    username: discord_downloader_api
    password: ${DB_PASSWORD}

  flyway:
    url: jdbc:postgresql://192.168.10.94:5432/discordDownloaderdb
    user: discord_downloader_flyway
    password: ${FLYWAY_PASSWORD}

downloader:
  s3:
    endpoint: http://192.168.10.96:3900
    bucket: discord-attachments
    access-key: ${S3_ACCESS_KEY}
    secret-key: ${S3_SECRET_KEY}
  ytdlp-path: /usr/local/bin/yt-dlp
  ffmpeg-path: /usr/bin/ffmpeg
  tmp-dir: /tmp/discord-downloads
  discord-max-file-bytes: 26214400  # 25 MB

discord:
  guild-id: ${GUILD_ID}

topics:
  inbound-interactions: discord.events.interaction.command
  inbound-commands: discord.events.message.command
  outbound-responses: discord.gateway.responses
  gateway-commands: discord.gateway.commands

# resilience4j — ver seção Resiliência acima para config completa

management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics, circuitbreakers, retries
  endpoint:
    health:
      show-details: always
  health:
    circuitbreakers:
      enabled: true
  metrics:
    export:
      prometheus:
        enabled: true
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT:http://localhost:4318/v1/traces}
```

---

## `build.gradle.kts`

```kotlin
plugins {
    java
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.discord"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Kafka
    implementation("org.springframework.kafka:spring-kafka")

    // Resilience4j
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("io.github.resilience4j:resilience4j-micrometer:2.2.0")
    implementation("org.springframework.boot:spring-boot-starter-aop") // obrigatório para annotations Resilience4j

    // Observabilidade
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // PostgreSQL + Flyway
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // AWS SDK v2 S3
    implementation(platform("software.amazon.awssdk:bom:2.26.0"))
    implementation("software.amazon.awssdk:s3")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("io.github.resilience4j:resilience4j-kotlin:2.2.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
```

---

## Estrutura de Pacotes

```
discord-downloader-bot/
├── build.gradle.kts
├── settings.gradle.kts
└── src/
    ├── main/
    │   ├── java/com/discord/downloader/
    │   │   ├── DiscordDownloaderApplication.java
    │   │   ├── config/
    │   │   │   ├── KafkaConfig.java               (VirtualThreadTaskExecutor para listeners)
    │   │   │   ├── S3Config.java                  (S3Client com endpoint Garage)
    │   │   │   ├── ResilienceConfig.java           (beans Resilience4j customizados se necessário)
    │   │   │   └── DownloaderProperties.java      (@ConfigurationProperties record)
    │   │   ├── domain/
    │   │   │   ├── DownloadSource.java            (entidade JPA — tabela download_sources)
    │   │   │   ├── DownloadJob.java               (entidade JPA — tabela download_jobs)
    │   │   │   ├── DownloadSourceRepository.java
    │   │   │   └── DownloadJobRepository.java
    │   │   ├── dto/
    │   │   │   ├── DiscordEventPayload.java        (record — envelope inbound)
    │   │   │   ├── GatewayResponse.java            (record — envelope outbound)
    │   │   │   └── DownloadRequest.java            (record — interno)
    │   │   ├── kafka/
    │   │   │   ├── EventConsumer.java              (consome interaction.command e message.command)
    │   │   │   └── ResponseProducer.java           (publica gateway.responses)
    │   │   ├── service/
    │   │   │   ├── DownloadOrchestrator.java       (orquestra fluxo + métricas Micrometer)
    │   │   │   ├── YtDlpService.java               (@CircuitBreaker + @Retry + @Bulkhead)
    │   │   │   ├── FfmpegService.java              (@CircuitBreaker + @Bulkhead)
    │   │   │   └── S3UploadService.java            (@CircuitBreaker + @Retry)
    │   │   ├── health/
    │   │   │   ├── YtDlpHealthIndicator.java
    │   │   │   ├── FfmpegHealthIndicator.java
    │   │   │   ├── GpuHealthIndicator.java
    │   │   │   └── S3HealthIndicator.java
    │   │   └── startup/
    │   │       └── CommandRegistrar.java           (ApplicationRunner — publica /download e .baixar)
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           └── V1__init.sql
    └── test/
        └── java/com/discord/downloader/
            ├── service/
            │   ├── YtDlpServiceTest.java
            │   ├── FfmpegServiceTest.java
            │   └── S3UploadServiceTest.java
            └── kafka/
                └── EventConsumerTest.java
```

### Detalhe: `KafkaConfig.java`

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
        ConsumerFactory<String, String> consumerFactory) {
    var factory = new ConcurrentKafkaListenerContainerFactory<String, String>();
    factory.setConsumerFactory(consumerFactory);
    factory.getContainerProperties()
            .setListenerTaskExecutor(new VirtualThreadTaskExecutor("kafka-listener-"));
    return factory;
}
```

### Detalhe: `DownloaderProperties.java`

```java
@ConfigurationProperties("downloader")
public record DownloaderProperties(
        S3Properties s3,
        String ytdlpPath,
        String ffmpegPath,
        String tmpDir,
        long discordMaxFileBytes
) {
    public record S3Properties(
            String endpoint,
            String bucket,
            String accessKey,
            String secretKey
    ) {}
}
```

### Detalhe: `YtDlpService.java` — anotações Resilience4j

```java
@CircuitBreaker(name = "ytdlp", fallbackMethod = "ytdlpFallback")
@Retry(name = "ytdlpRetry")
@Bulkhead(name = "ytdlpBulkhead")
public DownloadResult execute(DownloadRequest request) {
    // ProcessBuilder yt-dlp
}

private DownloadResult ytdlpFallback(DownloadRequest request, Throwable t) {
    // publicar EPHEMERAL_REPLY de indisponibilidade
}
```

### Detalhe: `DownloadOrchestrator.java` — métricas Micrometer

```java
@Autowired MeterRegistry meterRegistry;

// no início do fluxo:
meterRegistry.counter("downloader.job.started",
                              "source_host", sourceHost, "qualidade", qualidade).increment();

// ao finalizar com sucesso:
meterRegistry.timer("downloader.job.duration",
                            "source_host", sourceHost, "qualidade", qualidade)
    .record(duration);
meterRegistry.counter("downloader.job.completed",
                              "source_host", sourceHost, "qualidade", qualidade).increment();
```

---

## Observações

- O `interactionToken` do Discord expira em ~15 minutos — para vídeos grandes, publicar uma resposta intermediária antes de iniciar o yt-dlp informando que o download está em progresso.
- Instagram com conteúdo privado retorna erro — tratar como fonte não disponível e responder com `EPHEMERAL_REPLY`.
- Diretório temporário deve ser limpo mesmo em caso de falha — usar `try/finally`.
- O consumer deve usar `groupId` `discord-downloader` para não conflitar com outros bots.
- Virtual Threads tornam o `ProcessBuilder.waitFor()` seguro com muitos downloads simultâneos.
- Spring Boot 4 usa Jackson 3 por padrão — verificar compatibilidade dos DTOs com `tools.jackson.*`.
- `spring-boot-starter-aop` é obrigatório para as anotações `@CircuitBreaker`, `@Retry` e `@Bulkhead` do Resilience4j funcionarem via proxy.
- O `bot_id` foi removido de todos os payloads de registro de comandos.