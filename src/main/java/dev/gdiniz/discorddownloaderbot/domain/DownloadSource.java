package dev.gdiniz.discorddownloaderbot.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "download_sources", schema = "downloader")
public class DownloadSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String host;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String formatSelector;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "text[]")
    private String[] extraArgs;

    @Column(nullable = false)
    private boolean requiresAuth = false;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public Long getId() { return id; }
    public String getHost() { return host; }
    public String getFormatSelector() { return formatSelector; }
    public String[] getExtraArgs() { return extraArgs; }
    public boolean isRequiresAuth() { return requiresAuth; }
    public boolean isEnabled() { return enabled; }
    public OffsetDateTime getCreatedAt() { return createdAt; }

    public static DownloadSource generic(String host) {
        var s = new DownloadSource();
        s.host = host;
        s.formatSelector = "best[ext=mp4]/best";
        s.extraArgs = new String[]{"--no-playlist"};
        s.enabled = true;
        return s;
    }
}
