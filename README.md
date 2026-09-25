# discord-downloader-bot

Microserviço responsável por processar requisições de download de mídia (YouTube, TikTok, Instagram, X/Twitter, etc.) a partir do Discord. O serviço opera de forma assíncrona e orientada a eventos via Kafka/Redpanda, sem comunicação direta com a Gateway API do Discord.

## 🚀 Tecnologias

- **Java 25** (LTS) com **Virtual Threads** habilitadas
- **Spring Boot 4.0.6** (Spring MVC + Virtual Threads, Spring Data JPA, Actuator)
- **Apache Kafka / Redpanda** (`spring-kafka`)
- **PostgreSQL** + **Flyway**
- **yt-dlp** + **ffmpeg** (via `ProcessBuilder`)
- **AWS SDK v2** (S3 / Garage)
- **Resilience4j** (CircuitBreaker, Retry, Bulkhead)
- **Micrometer + OpenTelemetry + Prometheus**
- **Kubernetes** + **GraalVM Native Image / Docker**

## 📚 Documentação

Toda a documentação detalhada do projeto está centralizada na pasta [`docs/`](docs/):

- 🏛️ **[Arquitetura e Estrutura](docs/ARCHITECTURE.md)** — Visão geral da stack, estrutura de pastas, componentes e fluxo de dados.
- 📊 **[Métricas e Observabilidade](docs/METRICS.md)** — Detalhamento das métricas Prometheus expostas via Actuator.
- 📋 **[Especificação Técnica](docs/discord-downloader-bot.md)** — Especificação e regras de negócio do microserviço.

## 🛠️ Como Executar

### Pré-requisitos
- JDK 25
- Docker / Podman (opcional para containers locais)
- `ffmpeg` e `yt-dlp` instalados localmente para testes fora de container

### Build e Testes
```bash
# Rodar testes
./gradlew test

# Compilar projeto
./gradlew build
```

### Execução Local
```bash
./gradlew bootRun
```
*(Variáveis de ambiente de banco, Kafka e S3 podem ser configuradas no ambiente ou via profiles)*