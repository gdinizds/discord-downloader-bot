#!/bin/sh
set -e

if [ -d /app/cookies-secret ]; then
    echo "Copying yt-dlp cookies to writable location..."
    mkdir -p /app/cookies
    cp /app/cookies-secret/*.txt /app/cookies/
fi

echo "Updating yt-dlp..."
if pip3 install --no-cache-dir --break-system-packages --quiet -U "yt-dlp[default,curl-cffi]"; then
    echo "yt-dlp updated: $(yt-dlp --version)"
else
    echo "yt-dlp update failed, continuing with existing version: $(yt-dlp --version)"
fi

exec java \
  -XX:+UseZGC \
  -Xms256m -Xmx1g \
  -XX:MaxDirectMemorySize=256m \
  -jar app.jar
