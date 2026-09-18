package dev.gdiniz.discorddownloaderbot.dto;

public record VideoProbe(long fileSizeBytes, double durationSeconds) {

    public boolean hasSizeInfo() {
        return fileSizeBytes > 0;
    }
}
