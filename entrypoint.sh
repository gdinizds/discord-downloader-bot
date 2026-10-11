#!/bin/sh
set -e

if [ -d /app/cookies-secret ]; then
    echo "Copying yt-dlp cookies to writable location..."
    mkdir -p /app/cookies
    cp /app/cookies-secret/*.txt /app/cookies/
fi

echo "Updating yt-dlp and gallery-dl..."
if pip3 install --no-cache-dir --break-system-packages --quiet -U "yt-dlp[default,curl-cffi]" gallery-dl; then
    echo "Tools updated: yt-dlp $(yt-dlp --version), gallery-dl $(gallery-dl --version)"
else
    echo "Tools update failed, continuing with existing versions"
fi

if [ -f "./discord-downloader-bot" ]; then
    echo "Starting GraalVM Native Image..."
    exec ./discord-downloader-bot
else
    echo "Starting JVM..."
    exec java \
      -XX:+UseZGC \
      -Xms256m -Xmx1g \
      -XX:MaxDirectMemorySize=256m \
      -jar app.jar
fi
