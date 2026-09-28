package dev.gdiniz.discorddownloaderbot.dto;

import java.nio.file.Path;

public record VideoProbe(long fileSizeBytes, double durationSeconds, Path infoJson) {

    public static VideoProbe unknown() {
        return new VideoProbe(0, 0, null);
    }

    public boolean hasSizeInfo() {
        return fileSizeBytes > 0;
    }

    public boolean hasInfoJson() {
        return infoJson != null;
    }
}
