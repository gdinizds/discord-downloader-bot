package dev.gdiniz.discorddownloaderbot.util;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ProcessRunner {

    public record Result(int exitCode, String output) {
        public boolean succeeded() {
            return exitCode == 0;
        }
    }

    private static final ExecutorService READERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);

    private ProcessRunner() {}

    public static Result run(List<String> command, Duration timeout, int maxLines, boolean includeStderr)
            throws IOException, InterruptedException, TimeoutException {
        var builder = new ProcessBuilder(command);
        if (includeStderr) {
            builder.redirectErrorStream(true);
        } else {
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        }
        return execute(builder, command, timeout, maxLines, false);
    }

    public static Result runWithStdoutTo(List<String> command, Path stdoutFile, Duration timeout, int maxStderrLines)
            throws IOException, InterruptedException, TimeoutException {
        var builder = new ProcessBuilder(command).redirectOutput(stdoutFile.toFile());
        return execute(builder, command, timeout, maxStderrLines, true);
    }

    private static Result execute(ProcessBuilder builder, List<String> command, Duration timeout,
                                  int maxLines, boolean readStderr)
            throws IOException, InterruptedException, TimeoutException {
        Process process = builder.start();
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try {
                var stream = readStderr ? process.getErrorStream() : process.getInputStream();
                return ProcessUtils.readProcessOutputBounded(stream, maxLines);
            } catch (IOException e) {
                return "";
            }
        }, READERS);

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            destroyTree(process);
            throw e;
        }
        if (!finished) {
            destroyTree(process);
            throw new TimeoutException("Process did not finish within " + timeout + ": " + command.getFirst());
        }
        return new Result(process.exitValue(), drain(output));
    }

    public static void destroyTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static String drain(CompletableFuture<String> output) throws InterruptedException {
        try {
            return output.get(DRAIN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException | TimeoutException e) {
            output.cancel(true);
            return "";
        }
    }
}
