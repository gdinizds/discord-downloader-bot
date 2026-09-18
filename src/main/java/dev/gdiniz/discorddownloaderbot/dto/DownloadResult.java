package dev.gdiniz.discorddownloaderbot.dto;

import java.nio.file.Path;

public record DownloadResult(
        Path filePath,
        String title,
        long fileSizeBytes,
        String extension
) {}
