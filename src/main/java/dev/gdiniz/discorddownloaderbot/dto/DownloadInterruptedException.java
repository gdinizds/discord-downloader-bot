package dev.gdiniz.discorddownloaderbot.dto;

public class DownloadInterruptedException extends DownloadException {

    public DownloadInterruptedException(String message, Throwable cause) {
        super(message, cause);
    }
}
