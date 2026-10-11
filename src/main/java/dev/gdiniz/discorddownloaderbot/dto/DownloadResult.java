package dev.gdiniz.discorddownloaderbot.dto;

import java.nio.file.Path;
import java.util.List;

public record DownloadResult(
        Path filePath,
        String title,
        long fileSizeBytes,
        String extension,
        List<Path> allFiles
) {
    public DownloadResult(Path filePath, String title, long fileSizeBytes, String extension) {
        this(filePath, title, fileSizeBytes, extension, filePath != null ? List.of(filePath) : List.of());
    }
}
