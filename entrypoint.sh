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
    exec ./discord-downloader-bot ${NATIVE_OPTS:-}
else
    echo "Starting GraalVM JVM..."

    DEFAULT_JVM_OPTS="
      -XX:+UnlockExperimentalVMOptions
      -XX:+EnableJVMCI
      -XX:+UseJVMCICompiler
      -Dgraal.TuneInlinerExploration=1
      -XX:+UseCompactObjectHeaders
      -XX:+UseZGC
      -XX:+AlwaysPreTouch
      -XX:+UseStringDeduplication
      -XX:InitialRAMPercentage=${JVM_INITIAL_RAM_PERCENTAGE:-50.0}
      -XX:MaxRAMPercentage=${JVM_MAX_RAM_PERCENTAGE:-75.0}
      -XX:MinRAMPercentage=${JVM_MIN_RAM_PERCENTAGE:-50.0}
      -XX:MaxDirectMemorySize=${JVM_MAX_DIRECT_MEMORY:-512m}
      -XX:+ExitOnOutOfMemoryError
      -XX:+CrashOnOutOfMemoryError
      -Djava.security.egd=file:/dev/./urandom
    "

    EXEC_JAVA_OPTS="${DEFAULT_JVM_OPTS} ${JAVA_OPTS:-}"
    EXEC_JAVA_OPTS=$(echo "$EXEC_JAVA_OPTS" | tr '\n' ' ' | tr -s ' ')

    exec java $EXEC_JAVA_OPTS -jar app.jar
fi
