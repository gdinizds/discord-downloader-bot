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
            boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
            for (String tool : List.of("yt-dlp", "gallery-dl", "ffmpeg", "ffprobe")) {
                try (var in = FakeTools.class.getResourceAsStream("/fake-tools/" + tool)) {
                    if (in == null) continue;
                    var target = dir.resolve(tool);
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                    target.toFile().setExecutable(true);
                }
                if (isWin) {
                    var cmdTarget = dir.resolve(tool + ".cmd");
                    String gitSh = "C:\\Program Files\\Git\\bin\\sh.exe";
                    String script;
                    if (tool.equals("ffmpeg")) {
                        script = "@echo off\r\necho ffmpeg version fake\r\nexit /b 0\r\n";
                    } else if (Files.exists(Path.of(gitSh))) {
                        script = "@echo off\r\n\"" + gitSh + "\" \"%~dp0" + tool + "\" %*\r\n";
                    } else {
                        script = "@echo off\r\nexit /b 0\r\n";
                    }
                    Files.writeString(cmdTarget, script);
                }
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static DownloaderProperties properties(Path toolsDir, Path tmpDir, int timeoutSeconds) {
        boolean isWin = System.getProperty("os.name").toLowerCase().contains("win");
        String ext = isWin ? ".cmd" : "";
        var s3 = new DownloaderProperties.S3Properties("http://garage:3900", "bucket", "key", "secret", "garage");
        return new DownloaderProperties(s3,
                toolsDir.resolve("yt-dlp" + ext).toString(),
                toolsDir.resolve("gallery-dl" + ext).toString(),
                toolsDir.resolve("ffmpeg" + ext).toString(),
                2,
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
