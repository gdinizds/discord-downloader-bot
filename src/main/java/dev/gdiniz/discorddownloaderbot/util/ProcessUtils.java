package dev.gdiniz.discorddownloaderbot.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * Utility for executing and reading CLI processes efficiently without unbounded memory allocation.
 */
public final class ProcessUtils {

    private ProcessUtils() {}

    /**
     * Reads output from an InputStream keeping at most {@code maxLines} in memory using a ring buffer.
     * Prevents excessive heap allocations and GC pauses during high-concurrency CLI operations.
     */
    public static String readProcessOutputBounded(InputStream inputStream, int maxLines) throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            var buffer = new ArrayDeque<String>(maxLines);
            String line;
            while ((line = reader.readLine()) != null) {
                if (buffer.size() >= maxLines) {
                    buffer.removeFirst();
                }
                buffer.addLast(line);
            }
            return String.join("\n", buffer).trim();
        }
    }
}
