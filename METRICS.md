# Métricas expostas — discord-downloader-bot

O serviço expõe métricas via **Micrometer**, publicadas em formato Prometheus no endpoint:

```
GET /actuator/prometheus
```

(também disponíveis, sem formatação Prometheus, em `GET /actuator/metrics` e `GET /actuator/metrics/{nome}`)

Endpoints do Actuator habilitados (`management.endpoints.web.exposure.include`): `health`, `info`, `prometheus`, `metrics`, `circuitbreakers`, `retries`.

---

## 1. Métricas de negócio (customizadas)

Registradas manualmente em `DownloadOrchestrator.java` via `MeterRegistry`.

| Métrica | Tipo | Tags | Quando é registrada |
|---|---|---|---|
| `downloader.job.started` | Counter | `source_host`, `qualidade` | Ao iniciar o processamento de um job de download |
| `downloader.job.completed` | Counter | `source_host`, `qualidade` | Ao concluir um job com sucesso (upload feito) |
| `downloader.job.failed` | Counter | `source_host`, `qualidade`, `reason` | Ao falhar um job (`reason` = `"Timeout"` ou o simple name da exceção) |
| `downloader.job.duration` | Timer | `source_host`, `qualidade` | Duração total do job (do início até o upload concluído) |
| `downloader.file.size` | DistributionSummary | `source_host` | Tamanho final (bytes) do arquivo enviado ao S3/Garage |

`source_host` é o host extraído da URL (ex.: `youtube.com`, `tiktok.com`, `instagram.com`, `x.com`). `qualidade` é o valor solicitado no comando (`1080p`, `720p`, etc.).

---

## 2. Métricas de resiliência (Resilience4j → Micrometer)

Expostas automaticamente pela dependência `resilience4j-micrometer`, uma série por *instance* configurada em `application.yaml`.

### Circuit Breakers (`resilience4j.circuitbreaker.*`)

| Instance | Usado em | Config (`slidingWindowSize` / `failureRateThreshold` / `waitDurationInOpenState`) |
|---|---|---|
| `ytdlp` | `YtDlpService.execute` | 10 / 50% / 30s |
| `ffmpeg` | `FfmpegService.encode`, `FfmpegService.encodeToSize` | 10 / 50% / 30s |
| `s3Upload` | `S3UploadService.upload` | 10 / 50% / 60s |
| `database` | *configurado, mas não vinculado a nenhum método no código atual* | 10 / 50% / 15s |

Principais séries geradas por instance: `resilience4j_circuitbreaker_calls_total`, `resilience4j_circuitbreaker_state`, `resilience4j_circuitbreaker_buffered_calls`, `resilience4j_circuitbreaker_failure_rate`, `resilience4j_circuitbreaker_slow_call_rate`.

### Retries (`resilience4j.retry.*`)

| Instance | Usado em | Config (`maxAttempts` / `waitDuration`) |
|---|---|---|
| `ytdlpRetry` | `YtDlpService.execute` | 2 / 2s (backoff exponencial x2) |
| `s3Retry` | `S3UploadService.upload` | 3 / 1s (backoff exponencial x2) |
| `databaseRetry` | *configurado, mas não vinculado a nenhum método no código atual* | 3 / 500ms (backoff exponencial x2) |

Principais séries: `resilience4j_retry_calls_total`.

### Bulkheads (`resilience4j.bulkhead.*`)

| Instance | Usado em | Config (`maxConcurrentCalls` / `maxWaitDuration`) |
|---|---|---|
| `ytdlpBulkhead` | `YtDlpService.execute` | 5 / 10s |
| `ffmpegBulkhead` | `FfmpegService.encode`, `FfmpegService.encodeToSize` | 3 / 10s |

Principais séries: `resilience4j_bulkhead_available_concurrent_calls`, `resilience4j_bulkhead_max_allowed_concurrent_calls`.

> Os estados/detalhes também ficam disponíveis (não como série temporal, mas como snapshot JSON) em `GET /actuator/circuitbreakers` e `GET /actuator/retries`.

---

## 3. Métricas de infraestrutura (auto-instrumentadas pelo Spring Boot Actuator)

Habilitadas automaticamente por terem as dependências correspondentes no classpath — sem código customizado.

| Categoria | Exemplos de séries | Origem |
|---|---|---|
| JVM (memória) | `jvm_memory_used_bytes`, `jvm_memory_committed_bytes`, `jvm_memory_max_bytes` | `micrometer-core` |
| JVM (GC) | `jvm_gc_pause_seconds`, `jvm_gc_memory_allocated_bytes` | `micrometer-core` (usa ZGC — `-XX:+UseZGC`) |
| JVM (threads) | `jvm_threads_live_threads`, `jvm_threads_states_threads` | `micrometer-core` (inclui virtual threads) |
| JVM (classes) | `jvm_classes_loaded_classes` | `micrometer-core` |
| Processo/SO | `process_cpu_usage`, `process_uptime_seconds`, `system_cpu_usage`, `disk_free_bytes`, `disk_total_bytes` | `micrometer-core` |
| HTTP server | `http_server_requests_seconds` (por `uri`, `method`, `status`) | Spring MVC + `spring-boot-starter-web` |
| Tomcat | `tomcat_sessions_active_current_sessions`, `tomcat_threads_*` | `spring-boot-starter-web` (embedded Tomcat) |
| Pool de conexões DB | `hikaricp_connections_active`, `hikaricp_connections_idle`, `hikaricp_connections_pending`, `hikaricp_connections_max` | HikariCP (`spring-boot-starter-data-jpa`) |
| Kafka client | `kafka_consumer_*`, `kafka_producer_*` (fetch rate, records-consumed-rate, request-latency etc., por `client-id`) | `spring-kafka` + Micrometer Kafka metrics binder |
| Logback | `logback_events_total` (por `level`) | `micrometer-core` |

---

## 4. Health & tracing (não são métricas, mas complementam observabilidade)

- `GET /actuator/health` — inclui checagem de circuit breakers (`management.health.circuitbreakers.enabled=true`) e espaço em disco (`management.health.diskspace.enabled=true`), com `show-details: always`.
- Tracing distribuído via **OpenTelemetry** (`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`), `sampling.probability: 1.0` (100% das requisições), exportado via OTLP para `OTEL_EXPORTER_OTLP_ENDPOINT` (default `http://localhost:4318/v1/traces`).

---

## Referências no código

- Métricas de negócio: `src/main/java/dev/gdiniz/discorddownloaderbot/service/DownloadOrchestrator.java`
- Config de exposição/endpoints: `src/main/resources/application.yaml` (bloco `management`)
- Config de resiliência: `src/main/resources/application.yaml` (bloco `resilience4j`)
- Anotações `@CircuitBreaker`/`@Retry`/`@Bulkhead`: `YtDlpService.java`, `FfmpegService.java`, `S3UploadService.java`
