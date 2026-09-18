package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.config.DownloaderProperties;
import dev.gdiniz.discorddownloaderbot.domain.DownloadJob;
import dev.gdiniz.discorddownloaderbot.domain.DownloadJobRepository;
import dev.gdiniz.discorddownloaderbot.domain.GuildConfigRepository;
import dev.gdiniz.discorddownloaderbot.domain.DownloadSource;
import dev.gdiniz.discorddownloaderbot.domain.DownloadSourceRepository;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import dev.gdiniz.discorddownloaderbot.dto.DownloadResult;
import dev.gdiniz.discorddownloaderbot.dto.GatewayResponse;
import dev.gdiniz.discorddownloaderbot.kafka.ResponseProducer;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class DownloadOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DownloadOrchestrator.class);

    private final YtDlpService ytDlpService;
    private final FfmpegService ffmpegService;
    private final S3UploadService s3UploadService;
    private final DownloadSourceRepository sourceRepository;
    private final DownloadJobRepository jobRepository;
    private final GuildConfigRepository guildConfigRepository;
    private final ResponseProducer responseProducer;
    private final DownloaderProperties properties;
    private final MeterRegistry meterRegistry;
    private final ScheduledExecutorService timeoutScheduler =
            Executors.newScheduledThreadPool(1, Thread.ofVirtual().name("download-timeout-", 0).factory());

    public DownloadOrchestrator(YtDlpService ytDlpService, FfmpegService ffmpegService,
                                 S3UploadService s3UploadService, DownloadSourceRepository sourceRepository,
                                 DownloadJobRepository jobRepository, GuildConfigRepository guildConfigRepository,
                                 ResponseProducer responseProducer,
                                 DownloaderProperties properties, MeterRegistry meterRegistry) {
        this.ytDlpService = ytDlpService;
        this.ffmpegService = ffmpegService;
        this.s3UploadService = s3UploadService;
        this.sourceRepository = sourceRepository;
        this.jobRepository = jobRepository;
        this.guildConfigRepository = guildConfigRepository;
        this.responseProducer = responseProducer;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public void process(DownloadRequest request) {
        var host = extractHost(request.url());
        boolean isInteraction = request.interactionToken() != null;
        long maxFileBytes = guildConfigRepository.findById(request.guildId())
                .map(c -> c.getMaxFileSizeBytes())
                .orElse(properties.discordMaxFileBytes());

        var span = Span.current();
        span.setAttribute("discord.correlationId", request.correlationId());
        span.setAttribute("discord.guildId", request.guildId() != null ? request.guildId() : "");
        span.setAttribute("download.sourceHost", host);
        span.setAttribute("download.qualidade", request.qualidade());

        meterRegistry.counter("downloader.job.started",
                "source_host", host, "qualidade", request.qualidade()).increment();

        var job = DownloadJob.create(request.correlationId(), request.guildId(), request.userId(),
                request.url(), host, request.qualidade());
        jobRepository.save(job);

        if (isInteraction) {
            responseProducer.send(GatewayResponse.deferredReply(
                    request.interactionToken(), request.correlationId(),
                    "⏳ Download em andamento...", null, null, false));
        } else {
            responseProducer.send(GatewayResponse.messageReply(
                    request.messageId(), request.channelId(), request.correlationId(),
                    "⏳ Download em andamento...", null, null, false));
        }

        long startTime = System.currentTimeMillis();
        Path workDir = Path.of(properties.tmpDir(), request.correlationId());

        var replied  = new AtomicBoolean(false);
        var timedOut = new AtomicBoolean(false);
        var worker   = Thread.currentThread();
        var timeoutTask = timeoutScheduler.schedule(() -> {
            if (timedOut.compareAndSet(false, true)) {
                log.warn("Processing timed out after {}s: correlationId={}",
                        properties.processingTimeoutSeconds(), request.correlationId());
                worker.interrupt();
            }
        }, properties.processingTimeoutSeconds(), TimeUnit.SECONDS);

        try {
            var source = resolveSource(host);

            var probe = ytDlpService.probe(request, source);
            if (probe.hasSizeInfo() && probe.fileSizeBytes() > maxFileBytes) {
                double sizeMb  = probe.fileSizeBytes() / 1_048_576.0;
                double limitMb = maxFileBytes / 1_048_576.0;
                if (!ffmpegService.canFitInSize(probe.durationSeconds(), maxFileBytes)) {
                    var msg = "❌ O vídeo tem aproximadamente **%.1f MB** e não é possível comprimir para o limite de **%.0f MB** deste servidor."
                            .formatted(sizeMb, limitMb);
                    replied.set(true);
                    responseProducer.send(buildReply(request, isInteraction, msg, null, null, true));
                    job.markFailed("Pre-validation: %.1f MB > limit %.0f MB, uncompressible".formatted(sizeMb, limitMb));
                    jobRepository.save(job);
                    return;
                }
                log.info("File (~{}MB) exceeds limit ({}MB) but compression is feasible — proceeding: correlationId={}",
                        (int) sizeMb, (int) limitMb, request.correlationId());
            } else if (!probe.hasSizeInfo() && probe.durationSeconds() > 0
                    && !ffmpegService.canFitInSize(probe.durationSeconds(), maxFileBytes)) {
                double limitMb   = maxFileBytes / 1_048_576.0;
                int durationMin  = (int) (probe.durationSeconds() / 60);
                var msg = "❌ O vídeo tem **%d min** de duração e não é possível comprimir para o limite de **%.0f MB** deste servidor."
                        .formatted(durationMin, limitMb);
                replied.set(true);
                responseProducer.send(buildReply(request, isInteraction, msg, null, null, true));
                job.markFailed("Pre-validation: %dmin duration uncompressible to %.0f MB".formatted(durationMin, limitMb));
                jobRepository.save(job);
                return;
            }

            job.markDownloading();
            jobRepository.save(job);

            var result = ytDlpService.execute(request, source);

            Path fileToUpload = result.filePath();
            if (ffmpegService.needsEncode(fileToUpload, request.qualidade())) {
                job.markEncoding();
                jobRepository.save(job);
                fileToUpload = ffmpegService.encode(fileToUpload, request.correlationId());
            }

            long actualSize = fileSize(fileToUpload);
            if (actualSize > maxFileBytes) {
                log.info("File too large ({}MB), attempting size-targeted compression: correlationId={}",
                        actualSize / 1_048_576, request.correlationId());
                job.markEncoding();
                jobRepository.save(job);
                Path compressed = null;
                try {
                    compressed = ffmpegService.encodeToSize(fileToUpload, request.correlationId(), maxFileBytes);
                } catch (Exception e) {
                    log.error("Compression failed: correlationId={}", request.correlationId(), e);
                }
                if (compressed != null) {
                    long compressedSize = fileSize(compressed);
                    if (compressedSize <= maxFileBytes) {
                        fileToUpload = compressed;
                        actualSize = compressedSize;
                    } else {
                        compressed = null;
                    }
                }
                if (compressed == null) {
                    double mb      = actualSize / 1_048_576.0;
                    double limitMb = maxFileBytes / 1_048_576.0;
                    var msg = "❌ O arquivo tem **%.1f MB** e não foi possível comprimir para o limite de **%.0f MB** deste servidor."
                            .formatted(mb, limitMb);
                    replied.set(true);
                    responseProducer.send(buildReply(request, isInteraction, msg, null, null, true));
                    job.markFailed("File too large after compression: %.1f MB".formatted(mb));
                    jobRepository.save(job);
                    return;
                }
            }

            job.markUploading();
            jobRepository.save(job);
            var s3Key = s3UploadService.upload(fileToUpload, request.guildId(), request.correlationId());

            String attachmentUrl = "%s/%s/%s".formatted(
                    properties.s3().endpoint(),
                    properties.s3().bucket(),
                    s3Key);

            var attachment = new GatewayResponse.Attachment(
                    attachmentUrl,
                    fileToUpload.getFileName().toString(),
                    result.title());

            replied.set(true);
            responseProducer.send(buildReply(request, isInteraction, result.title(), null, List.of(attachment), true));

            job.markDone(result.fileSizeBytes(), s3Key);
            jobRepository.save(job);

            long elapsed = System.currentTimeMillis() - startTime;
            meterRegistry.timer("downloader.job.duration", "source_host", host, "qualidade", request.qualidade())
                    .record(elapsed, TimeUnit.MILLISECONDS);
            meterRegistry.counter("downloader.job.completed", "source_host", host, "qualidade", request.qualidade()).increment();
            meterRegistry.summary("downloader.file.size", "source_host", host).record(result.fileSizeBytes());

        } catch (DownloadException e) {
            Thread.interrupted(); // clear interrupt flag before DB ops to avoid HikariCP CannotCreateTransactionException
            if (timedOut.get()) {
                log.warn("Processing timed out: correlationId={}", request.correlationId());
            } else {
                log.error("Download failed: correlationId={}", request.correlationId(), e);
            }
            if (replied.compareAndSet(false, true)) {
                var msg = timedOut.get()
                        ? "⏱ Tempo limite de **%ds** atingido. Tente com um vídeo mais curto."
                                .formatted(properties.processingTimeoutSeconds())
                        : "❌ Não foi possível baixar o conteúdo. Verifique se o link é válido e tente novamente.";
                responseProducer.send(buildReply(request, isInteraction, msg, null, null, true));
            }
            job.markFailed(timedOut.get() ? "Timeout" : e.getMessage());
            jobRepository.save(job);
            meterRegistry.counter("downloader.job.failed", "source_host", host,
                    "qualidade", request.qualidade(),
                    "reason", timedOut.get() ? "Timeout" : e.getClass().getSimpleName()).increment();
        } finally {
            timeoutTask.cancel(false);
            cleanup(workDir);
        }
    }

    private GatewayResponse buildReply(DownloadRequest request, boolean isInteraction,
                                        String content, List<GatewayResponse.Embed> embeds,
                                        List<GatewayResponse.Attachment> attachments, Boolean finished) {
        if (isInteraction) {
            return GatewayResponse.deferredReply(request.interactionToken(), request.correlationId(), content, embeds, attachments, finished);
        }
        return GatewayResponse.updateMessage(request.messageId(), request.channelId(), request.correlationId(), content, embeds, attachments, finished);
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new DownloadException("Failed to read file size: " + path, e);
        }
    }

    private DownloadSource resolveSource(String host) {
        var found = sourceRepository.findByHostAndEnabledTrue(host);
        if (found.isEmpty()) {
            log.warn("No configured source for host '{}' — using generic yt-dlp fallback", host);
        }
        return found.orElseGet(() -> DownloadSource.generic(host));
    }

    private String extractHost(String url) {
        try {
            var uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return "unknown";
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String friendlyHost(String host) {
        return switch (host) {
            case "youtube.com", "youtu.be"                           -> "YouTube";
            case "twitter.com", "x.com"                              -> "X/Twitter";
            case "instagram.com"                                      -> "Instagram";
            case "tiktok.com", "vt.tiktok.com",
                 "vm.tiktok.com", "m.tiktok.com"                     -> "TikTok";
            default                                                   -> host;
        };
    }

    private void cleanup(Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            log.warn("Failed to clean up temp dir: {}", dir, e);
        }
    }
}
