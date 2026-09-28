package dev.gdiniz.discorddownloaderbot.testsupport;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class FakeTools {

    private FakeTools() {}

    public static Path install(Path dir) {
        try {
            Files.createDirectories(dir);
            for (String tool : List.of("yt-dlp", "ffmpeg", "ffprobe")) {
                try (var in = FakeTools.class.getResourceAsStream("/fake-tools/" + tool)) {
                    if (in == null) throw new IllegalStateException("missing fake tool " + tool);
                    var target = dir.resolve(tool);
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    target.toFile().setExecutable(true);
                }
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static DownloaderProperties properties(Path toolsDir, Path tmpDir, int timeoutSeconds) {
        var s3 = new DownloaderProperties.S3Properties("http://garage:3900", "bucket", "key", "secret", "garage");
        return new DownloaderProperties(s3,
                toolsDir.resolve("yt-dlp").toString(),
                toolsDir.resolve("ffmpeg").toString(),
                tmpDir.toString(), 26_214_400L, timeoutSeconds);
    }

    public static List<String> calls(Path toolsDir) {
        try {
            var log = toolsDir.resolve("calls.log");
            return Files.exists(log) ? Files.readAllLines(log) : List.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
