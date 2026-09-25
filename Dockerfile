# ─── Stage 1: Build (JAR normal, JDK Temurin) ────────────
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle/
RUN ./gradlew dependencies --no-daemon -q

COPY src src/
RUN ./gradlew bootJar --no-daemon -x test

# ─── Stage 2: Runtime (Ubuntu + JRE + CLI Tools) ────────
FROM eclipse-temurin:25-jre-noble AS runtime
WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        python3 \
        python3-pip \
        curl \
        unzip \
        ca-certificates \
    && pip3 install --no-cache-dir --break-system-packages -U "yt-dlp[default,curl-cffi]" \
    && curl -fsSL https://deno.land/install.sh | DENO_INSTALL=/usr/local sh \
    && apt-get purge -y --auto-remove curl unzip \
    && rm -rf /var/lib/apt/lists/*

RUN groupadd -r app && useradd -r -g app -u 1001 app \
    && mkdir -p /tmp/discord-downloads \
    && chown app:app /tmp/discord-downloads \
    && chown -R app:app /usr/local/bin /usr/local/lib/python3*/dist-packages

COPY --from=build --chown=app:app /workspace/build/libs/*.jar ./app.jar
COPY --chown=app:app entrypoint.sh entrypoint.sh
RUN chmod +x ./entrypoint.sh

USER app

EXPOSE 8080

ENTRYPOINT ["./entrypoint.sh"]