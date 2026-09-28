package dev.gdiniz.discorddownloaderbot.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class ProcessRunnerTest {

    @TempDir
    Path tmp;

    @Test
    void returnsExitCodeAndOutput() throws Exception {
        var result = ProcessRunner.run(List.of("sh", "-c", "echo one; echo two >&2; exit 3"),
                Duration.ofSeconds(10), 10, true);

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.succeeded()).isFalse();
        assertThat(result.output()).contains("one").contains("two");
    }

    @Test
    void keepsOnlyTheLastLines() throws Exception {
        var result = ProcessRunner.run(List.of("sh", "-c", "for i in 1 2 3 4 5; do echo line$i; done"),
                Duration.ofSeconds(10), 2, true);

        assertThat(result.output()).isEqualTo("line4\nline5");
    }

    @Test
    void timeoutKillsTheWholeProcessTree() throws Exception {
        var pidFile = tmp.resolve("child.pid");

        assertThatThrownBy(() -> ProcessRunner.run(
                List.of("sh", "-c", "sleep 60 & echo $! > " + pidFile + "; wait"),
                Duration.ofMillis(500), 10, true))
                .isInstanceOf(TimeoutException.class);

        long childPid = Long.parseLong(Files.readString(pidFile).strip());
        await().atMost(Duration.ofSeconds(5)).until(() ->
                ProcessHandle.of(childPid).map(p -> !p.isAlive()).orElse(true));
    }

    @Test
    void interruptionKillsTheProcess() throws Exception {
        var pidFile = tmp.resolve("interrupted.pid");
        var failure = new AtomicReference<Throwable>();
        var worker = Thread.ofVirtual().start(() -> {
            try {
                ProcessRunner.run(List.of("sh", "-c", "echo $$ > " + pidFile + "; sleep 60"),
                        Duration.ofSeconds(60), 10, true);
            } catch (Throwable t) {
                failure.set(t);
            }
        });

        await().atMost(Duration.ofSeconds(5)).until(() -> Files.exists(pidFile) && Files.size(pidFile) > 0);
        worker.interrupt();
        worker.join(Duration.ofSeconds(5));

        assertThat(failure.get()).isInstanceOf(InterruptedException.class);
        long pid = Long.parseLong(Files.readString(pidFile).strip());
        await().atMost(Duration.ofSeconds(5)).until(() ->
                ProcessHandle.of(pid).map(p -> !p.isAlive()).orElse(true));
    }

    @Test
    void stdoutCanBeRedirectedToFileWhileStderrIsCaptured() throws Exception {
        var out = tmp.resolve("out.json");

        var result = ProcessRunner.runWithStdoutTo(List.of("sh", "-c", "echo '{\"a\":1}'; echo warn >&2"),
                out, Duration.ofSeconds(10), 10);

        assertThat(result.succeeded()).isTrue();
        assertThat(Files.readString(out).strip()).isEqualTo("{\"a\":1}");
        assertThat(result.output()).isEqualTo("warn");
    }

    @Test
    void concurrentRunsDoNotInterfere() {
        var futures = java.util.stream.IntStream.range(0, 8)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return ProcessRunner.run(List.of("sh", "-c", "echo " + i), Duration.ofSeconds(10), 5, true).output();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }))
                .toList();

        for (int i = 0; i < futures.size(); i++) {
            assertThat(futures.get(i).join()).isEqualTo(String.valueOf(i));
        }
    }
}
