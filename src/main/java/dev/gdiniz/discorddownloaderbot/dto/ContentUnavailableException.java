package dev.gdiniz.discorddownloaderbot.dto;

public class ContentUnavailableException extends DownloadException {

    public ContentUnavailableException(String message) {
        super(message);
    }
}
