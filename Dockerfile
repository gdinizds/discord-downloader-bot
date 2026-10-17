ARG GRAALVM_IMAGE=container-registry.oracle.com/graalvm/native-image:25
FROM ${GRAALVM_IMAGE} AS build
WORKDIR /workspace
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle/
RUN ./gradlew dependencies --no-daemon -q

COPY src src/
RUN ./gradlew nativeCompile --no-daemon -x test

FROM ubuntu:noble AS runtime
WORKDIR /app

ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        python3 \
        python3-pip \
        curl \
        unzip \
        ca-certificates \
        libstdc++6 \
        zlib1g \
    && pip3 install --no-cache-dir --break-system-packages -U "yt-dlp[default,curl-cffi]" gallery-dl \
    && curl -fsSL https://deno.land/install.sh | DENO_INSTALL=/usr/local sh \
    && apt-get purge -y --auto-remove curl unzip \
    && rm -rf /var/lib/apt/lists/*

ENV PYTHONUSERBASE=/app/pydeps
ENV PATH=/app/pydeps/bin:$PATH

RUN groupadd -r app && useradd -r -g app -u 1001 app \
    && mkdir -p /tmp/discord-downloads /app/pydeps /app/cookies \
    && chown -R app:app /tmp/discord-downloads /app/pydeps /app/cookies

COPY --from=build --chown=app:app /workspace/build/native/nativeCompile/discord-downloader-bot ./discord-downloader-bot
COPY --chown=app:app entrypoint.sh entrypoint.sh
RUN chmod +x ./entrypoint.sh ./discord-downloader-bot

USER app

EXPOSE 8080

ENTRYPOINT ["./entrypoint.sh"]


