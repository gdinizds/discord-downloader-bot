#!/bin/sh
set -e

export LANG=C.UTF-8
export LC_ALL=C.UTF-8

if [ -d /app/cookies-secret ]; then
    echo "Copying downloader cookies to writable location..."
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
    exec ./discord-downloader-bot -XX:MaximumHeapSizePercent=${NATIVE_MAX_HEAP_PERCENT:-50} ${NATIVE_OPTS:-}
else
    echo "Starting GraalVM JVM..."

    DEFAULT_JVM_OPTS="
      -Dfile.encoding=UTF-8
      -Dsun.jnu.encoding=UTF-8
      -XX:+UnlockExperimentalVMOptions
      -XX:+EnableJVMCI
      -XX:+UseJVMCICompiler
      -Djdk.graal.CompilerConfiguration=enterprise
      -Djdk.graal.Vectorization=true
      -Djdk.graal.UsePriorityInlining=true
      -Djdk.graal.TuneInlinerExploration=1
      -XX:+TieredCompilation
      -XX:+UseCompactObjectHeaders
      -XX:+UseZGC
      -XX:+AlwaysPreTouch
      -XX:+UseStringDeduplication
      -XX:InitialRAMPercentage=${JVM_INITIAL_RAM_PERCENTAGE:-30.0}
      -XX:MaxRAMPercentage=${JVM_MAX_RAM_PERCENTAGE:-65.0}
      -XX:MinRAMPercentage=${JVM_MIN_RAM_PERCENTAGE:-30.0}
      -XX:MaxDirectMemorySize=${JVM_MAX_DIRECT_MEMORY:-512m}
      -XX:+ExitOnOutOfMemoryError
      -XX:+CrashOnOutOfMemoryError
      -Djava.security.egd=file:/dev/./urandom
      -Dnetworkaddress.cache.ttl=30
      -Dnetworkaddress.cache.negative.ttl=10
    "

    EXEC_JAVA_OPTS="${DEFAULT_JVM_OPTS} ${JAVA_OPTS:-}"
    EXEC_JAVA_OPTS=$(echo "$EXEC_JAVA_OPTS" | tr '\n' ' ' | tr -s ' ')

    exec java $EXEC_JAVA_OPTS -jar app.jar
fi
