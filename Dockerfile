# ─── Stage 1: Build (GraalVM JDK 25) ───────────────────────────
ARG GRAALVM_IMAGE=container-registry.oracle.com/graalvm/jdk:25
FROM ${GRAALVM_IMAGE} AS build
WORKDIR /workspace
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle/
RUN ./gradlew dependencies --no-daemon -q

COPY src src/
RUN ./gradlew bootJar --no-daemon -x test

# ─── Stage 2: Runtime (Ubuntu noble + GraalVM JDK + CLI Tools) ─
FROM ubuntu:noble AS runtime
WORKDIR /app

# UTF-8 Locale
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

# GraalVM JDK environment
ENV JAVA_HOME=/opt/graalvm
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# Install CLI tools and dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        python3 \
        python3-pip \
        curl \
        unzip \
        ca-certificates \
    && pip3 install --no-cache-dir --break-system-packages -U "yt-dlp[default,curl-cffi]" gallery-dl \
    && curl -fsSL https://deno.land/install.sh | DENO_INSTALL=/usr/local sh \
    && apt-get purge -y --auto-remove curl unzip \
    && rm -rf /var/lib/apt/lists/*

# Copy GraalVM JDK from build stage
ARG GRAALVM_JDK_DIR=/usr/lib64/graalvm/graalvm-java25
COPY --from=build ${GRAALVM_JDK_DIR} ${JAVA_HOME}

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
