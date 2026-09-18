package dev.gdiniz.discorddownloaderbot.dto;

public class DownloadException extends RuntimeException {

    public DownloadException(String message) {
        super(message);
    }

    public DownloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
