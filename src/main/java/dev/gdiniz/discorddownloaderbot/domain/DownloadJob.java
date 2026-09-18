package dev.gdiniz.discorddownloaderbot.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "download_jobs", schema = "downloader")
public class DownloadJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String correlationId;

    @Column(nullable = false, length = 30)
    private String guildId;

    @Column(nullable = false, length = 30)
    private String userId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String url;

    @Column(length = 100)
    private String sourceHost;

    @Column(length = 20)
    private String qualidade;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DownloadStatus status;

    private Long fileSizeBytes;

    @Column(columnDefinition = "TEXT")
    private String s3Key;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    private OffsetDateTime completedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public static DownloadJob create(String correlationId, String guildId, String userId,
                                     String url, String sourceHost, String qualidade) {
        var job = new DownloadJob();
        job.correlationId = correlationId;
        job.guildId = guildId;
        job.userId = userId;
        job.url = url;
        job.sourceHost = sourceHost;
        job.qualidade = qualidade;
        job.status = DownloadStatus.PENDING;
        return job;
    }

    public void markDownloading() { this.status = DownloadStatus.DOWNLOADING; }
    public void markEncoding()    { this.status = DownloadStatus.ENCODING; }
    public void markUploading()   { this.status = DownloadStatus.UPLOADING; }

    public void markDone(long fileSizeBytes, String s3Key) {
        this.status = DownloadStatus.DONE;
        this.fileSizeBytes = fileSizeBytes;
        this.s3Key = s3Key;
        this.completedAt = OffsetDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = DownloadStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = OffsetDateTime.now();
    }

    public Long getId()              { return id; }
    public String getCorrelationId() { return correlationId; }
    public String getGuildId()       { return guildId; }
    public String getUserId()        { return userId; }
    public String getUrl()           { return url; }
    public String getSourceHost()    { return sourceHost; }
    public String getQualidade()     { return qualidade; }
    public DownloadStatus getStatus(){ return status; }
    public Long getFileSizeBytes()   { return fileSizeBytes; }
    public String getS3Key()         { return s3Key; }
    public String getErrorMessage()  { return errorMessage; }
    public OffsetDateTime getCreatedAt()   { return createdAt; }
    public OffsetDateTime getCompletedAt() { return completedAt; }
}
