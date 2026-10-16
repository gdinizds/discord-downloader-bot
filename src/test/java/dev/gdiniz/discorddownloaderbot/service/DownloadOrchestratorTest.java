package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.domain.DownloadJob;
import dev.gdiniz.discorddownloaderbot.domain.DownloadJobRepository;
import dev.gdiniz.discorddownloaderbot.domain.DownloadSourceRepository;
import dev.gdiniz.discorddownloaderbot.domain.DownloadStatus;
import dev.gdiniz.discorddownloaderbot.dto.ContentUnavailableException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadInterruptedException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import dev.gdiniz.discorddownloaderbot.dto.DownloadResult;
import dev.gdiniz.discorddownloaderbot.dto.GatewayResponse;
import dev.gdiniz.discorddownloaderbot.kafka.ResponseProducer;
import dev.gdiniz.discorddownloaderbot.testsupport.FakeTools;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DownloadOrchestratorTest {

    @TempDir
    Path tmp;

    private final GalleryDlService galleryDl = mock(GalleryDlService.class);
    private final DirectMediaDownloadService directDownload = mock(DirectMediaDownloadService.class);
    private final FfmpegService ffmpeg = mock(FfmpegService.class);
    private final S3UploadService s3 = mock(S3UploadService.class);
    private final DownloadSourceRepository sources = mock(DownloadSourceRepository.class);
    private final DownloadJobRepository jobs = mock(DownloadJobRepository.class);
    private final GuildConfigCache guildConfigs = mock(GuildConfigCache.class);
    private final ResponseProducer responses = mock(ResponseProducer.class);
    private final List<DownloadStatus> savedStatuses = new ArrayList<>();
    private final UrlPolicy urlPolicy = new UrlPolicy(host -> new InetAddress[]{
            InetAddress.getByAddress(host, new byte[]{(byte) 142, (byte) 250, 0, 1})});

    private DownloadOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        when(jobs.save(any())).thenAnswer(inv -> {
            DownloadJob job = inv.getArgument(0);
            savedStatuses.add(job.getStatus());
            return job;
        });
        when(guildConfigs.get(any())).thenReturn(Optional.empty());
        when(sources.findByHostAndEnabledTrue(anyString())).thenReturn(Optional.empty());
        when(ffmpeg.canFitInSize(anyDouble(), anyLong())).thenReturn(true);
        when(directDownload.isDirectMediaUrl(anyString())).thenReturn(false);
        orchestrator = orchestrator(10);
    }

    @Test
    void successfulDownloadRepliesWithAttachmentAndMarksJobDone() throws Exception {
        var file = downloaded("corr-ok", "clip.mp4", 2048);
        when(galleryDl.execute(any(), any(), anyLong())).thenReturn(new DownloadResult(file, "Meu vídeo", 2048, "mp4"));
        when(s3.upload(any(), eq("guild-1"), eq("corr-ok"), anyInt())).thenReturn("guild-1/corr-ok.mp4");

        orchestrator.process(interaction("corr-ok", "https://youtube.com/watch?v=ok"));

        var sent = sentResponses();
        assertThat(sent.getFirst().content()).isEqualTo(DownloadOrchestrator.MSG_IN_PROGRESS);
        var last = sent.getLast();
        assertThat(last.responseType()).isEqualTo("DEFERRED_REPLY");
        assertThat(last.finished()).isTrue();
        assertThat(last.attachments()).singleElement().satisfies(att -> {
            assertThat(att.url()).isEqualTo("http://garage:3900/bucket/guild-1/corr-ok.mp4");
            assertThat(att.name()).isEqualTo("Meu vídeo.mp4");
        });
        assertThat(savedStatuses).last().isEqualTo(DownloadStatus.DONE);
        assertThat(orchestrator.inFlight()).isZero();
    }

    @Test
    void directMediaUrlTakesFastPath() throws Exception {
        var image = downloaded("corr-fast", "image.png", 5000);
        when(directDownload.isDirectMediaUrl("https://example.com/image.png")).thenReturn(true);
        when(directDownload.download(any(), anyLong())).thenReturn(new DownloadResult(image, "image", 5000, "png"));
        when(s3.upload(any(), eq("guild-1"), eq("corr-fast"), anyInt())).thenReturn("guild-1/corr-fast.png");
        when(ffmpeg.isImage(image)).thenReturn(true);

        orchestrator.process(interaction("corr-fast", "https://example.com/image.png"));

        verifyNoInteractions(galleryDl);
        verify(directDownload).download(any(), anyLong());
        assertThat(savedStatuses).last().isEqualTo(DownloadStatus.DONE);
    }

    @Test
    void fallbackToDirectDownloadWhenGalleryDlFails() throws Exception {
        var photo = downloaded("corr-direct", "photo.jpg", 1024);
        when(galleryDl.execute(any(), any(), anyLong())).thenThrow(new DownloadException("unsupported url"));
        when(directDownload.download(any(), anyLong())).thenReturn(new DownloadResult(photo, "foto", 1024, "jpg"));
        when(s3.upload(any(), eq("guild-1"), eq("corr-direct"), anyInt())).thenReturn("guild-1/corr-direct.jpg");
        when(ffmpeg.isImage(photo)).thenReturn(true);

        orchestrator.process(interaction("corr-direct", "https://custom-site.com/photo"));

        var last = sentResponses().getLast();
        assertThat(last.finished()).isTrue();
        assertThat(last.attachments()).singleElement().satisfies(att -> {
            assertThat(att.url()).isEqualTo("http://garage:3900/bucket/guild-1/corr-direct.jpg");
            assertThat(att.name()).isEqualTo("foto.jpg");
        });
        assertThat(savedStatuses).last().isEqualTo(DownloadStatus.DONE);
    }

    @Test
    void multiFileAttachmentsFromGalleryAreAllUploadedAndSent() throws Exception {
        var photo1 = downloaded("corr-multi", "img_1.jpg", 1000);
        var photo2 = downloaded("corr-multi", "img_2.jpg", 1200);
        var photo3 = downloaded("corr-multi", "img_3.jpg", 1400);
        var files = List.of(photo1, photo2, photo3);

        when(galleryDl.execute(any(), any(), anyLong())).thenReturn(new DownloadResult(photo1, "album", 3600, "jpg", files));
        when(ffmpeg.isImage(any())).thenReturn(true);
        when(s3.upload(eq(photo1), eq("guild-1"), eq("corr-multi"), eq(0))).thenReturn("guild-1/corr-multi.jpg");
        when(s3.upload(eq(photo2), eq("guild-1"), eq("corr-multi"), eq(1))).thenReturn("guild-1/corr-multi_1.jpg");
        when(s3.upload(eq(photo3), eq("guild-1"), eq("corr-multi"), eq(2))).thenReturn("guild-1/corr-multi_2.jpg");

        orchestrator.process(interaction("corr-multi", "https://instagram.com/p/carousel"));

        var last = sentResponses().getLast();
        assertThat(last.finished()).isTrue();
        assertThat(last.attachments()).hasSize(3);
        assertThat(last.attachments().get(0).name()).isEqualTo("album_1.jpg");
        assertThat(last.attachments().get(1).name()).isEqualTo("album_2.jpg");
        assertThat(last.attachments().get(2).name()).isEqualTo("album_3.jpg");
        assertThat(savedStatuses).last().isEqualTo(DownloadStatus.DONE);
    }

    @Test
    void unexpectedErrorStillAnswersTheUserAndFailsTheJob() throws Exception {
        var file = downloaded("corr-boom", "clip.mp4", 2048);
        when(galleryDl.execute(any(), any(), anyLong())).thenReturn(new DownloadResult(file, "clip", 2048, "mp4"));
        when(s3.upload(any(), any(), any(), anyInt())).thenThrow(new IllegalStateException("socket closed"));

        orchestrator.process(interaction("corr-boom", "https://youtube.com/watch?v=ok"));

        var last = sentResponses().getLast();
        assertThat(last.content()).isEqualTo(DownloadOrchestrator.MSG_UNEXPECTED);
        assertThat(last.finished()).isTrue();
        assertThat(savedStatuses).last().isEqualTo(DownloadStatus.FAILED);
    }

    @Test
    void internalUrlsAreRejectedBeforeAnyWork() {
        var policy = new UrlPolicy(host -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{10, 0, 0, 1})});
        var strict = new DownloadOrchestrator(galleryDl, directDownload, ffmpeg, s3, sources, jobs, guildConfigs, responses,
                FakeTools.properties(tmp, tmp.resolve("work"), 10), new SimpleMeterRegistry(), policy);

        strict.process(interaction("corr-ssrf", "http://internal.example/admin"));

        assertThat(sentResponses()).singleElement()
                .extracting(GatewayResponse::content).isEqualTo(DownloadOrchestrator.MSG_INVALID_URL);
        verifyNoInteractions(galleryDl, s3);
        verify(jobs, never()).save(any());
    }

    @Test
    void redeliveredEventIsIgnored() {
        when(jobs.existsByCorrelationId("corr-dup")).thenReturn(true);

        orchestrator.process(interaction("corr-dup", "https://youtube.com/watch?v=ok"));

        verifyNoInteractions(galleryDl, responses);
        verify(jobs, never()).save(any());
    }

    @Test
    void unavailableContentGetsASpecificMessage() {
        when(galleryDl.execute(any(), any(), anyLong())).thenThrow(new ContentUnavailableException("private video"));

        orchestrator.process(interaction("corr-private", "https://youtube.com/watch?v=private"));

        assertThat(sentResponses().getLast().content()).isEqualTo(DownloadOrchestrator.MSG_UNAVAILABLE);
        assertThat(savedStatuses).last().isEqualTo(DownloadStatus.FAILED);
    }

    @Test
    void messageCommandFailingBeforeTheProgressMessageRepliesInsteadOfEditing() {
        doThrow(new IllegalStateException("database down")).when(jobs).save(any());

        orchestrator.process(messageCommand("corr-db", "https://youtube.com/watch?v=ok"));

        var only = sentResponses().getLast();
        assertThat(only.responseType()).isEqualTo("REPLY");
        assertThat(only.messageId()).isEqualTo("user-msg-1");
        assertThat(only.content()).isEqualTo(DownloadOrchestrator.MSG_UNEXPECTED);
    }

    @Test
    void messageCommandEditsTheProgressMessageOnceAnnounced() {
        when(galleryDl.execute(any(), any(), anyLong())).thenThrow(new ContentUnavailableException("removed"));

        orchestrator.process(messageCommand("corr-edit", "https://youtube.com/watch?v=gone"));

        var sent = sentResponses();
        assertThat(sent.getFirst().responseType()).isEqualTo("REPLY");
        assertThat(sent.getLast().responseType()).isEqualTo("UPDATE_MESSAGE");
    }

    @Test
    void processingTimeoutInterruptsTheWorkerAndTellsTheUser() {
        var quick = orchestrator(1);
        when(galleryDl.execute(any(), any(), anyLong())).thenAnswer(inv -> {
            try {
                TimeUnit.SECONDS.sleep(30);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DownloadInterruptedException("interrupted", e);
            }
            return null;
        });

        long start = System.nanoTime();
        quick.process(interaction("corr-slow", "https://youtube.com/watch?v=slow"));

        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(10);
        assertThat(sentResponses().getLast().content()).contains("Tempo limite");
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void imagesAboveTheLimitAreNotCompressed() throws Exception {
        var image = downloaded("corr-img", "photo.jpg", 30_000_000);
        when(galleryDl.execute(any(), any(), anyLong())).thenReturn(new DownloadResult(image, "photo", 30_000_000, "jpg"));
        when(ffmpeg.isImage(image)).thenReturn(true);

        orchestrator.process(interaction("corr-img", "https://youtube.com/watch?v=img"));

        verify(ffmpeg, never()).encodeToSize(any(), anyString(), anyLong());
        assertThat(sentResponses().getLast().content()).contains("não foi possível comprimir");
    }

    @Test
    void workDirectoryIsRemovedAfterProcessing() throws Exception {
        var file = downloaded("corr-clean", "clip.mp4", 10);
        var workDir = file.getParent();
        when(galleryDl.execute(any(), any(), anyLong())).thenReturn(new DownloadResult(file, "clip", 10, "mp4"));
        when(s3.upload(any(), any(), any(), anyInt())).thenReturn("key");

        orchestrator.process(interaction("corr-clean", "https://youtube.com/watch?v=ok"));

        assertThat(workDir).doesNotExist();
    }

    @Test
    void shutdownWaitsForInFlightDownloads() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        doAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            throw new ContentUnavailableException("gone");
        }).when(galleryDl).execute(any(), any(), anyLong());

        var worker = Thread.ofVirtual().start(() ->
                orchestrator.process(interaction("corr-drain", "https://youtube.com/watch?v=ok")));
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .until(() -> orchestrator.inFlight() == 1);

        var shutdown = Thread.ofVirtual().start(orchestrator::destroy);
        shutdown.join(300);
        assertThat(shutdown.isAlive()).isTrue();

        release.countDown();
        shutdown.join(5000);
        worker.join(5000);
        assertThat(shutdown.isAlive()).isFalse();
        assertThat(orchestrator.inFlight()).isZero();
    }

    private DownloadOrchestrator orchestrator(int timeoutSeconds) {
        return new DownloadOrchestrator(galleryDl, directDownload, ffmpeg, s3, sources, jobs, guildConfigs, responses,
                FakeTools.properties(tmp, tmp.resolve("work"), timeoutSeconds), new SimpleMeterRegistry(), urlPolicy);
    }

    private Path downloaded(String correlationId, String name, long size) throws Exception {
        var dir = Files.createDirectories(tmp.resolve("work").resolve(correlationId));
        var file = dir.resolve(name);
        try (var raf = new java.io.RandomAccessFile(file.toFile(), "rw")) {
            raf.setLength(size);
        }
        return file;
    }

    private List<GatewayResponse> sentResponses() {
        var captor = ArgumentCaptor.forClass(GatewayResponse.class);
        verify(responses, atLeastOnce()).send(captor.capture());
        return captor.getAllValues();
    }

    private static DownloadRequest interaction(String correlationId, String url) {
        return new DownloadRequest(correlationId, "guild-1", "channel-1", "user-1", "token-1", null, url, "original");
    }

    private static DownloadRequest messageCommand(String correlationId, String url) {
        return new DownloadRequest(correlationId, "guild-1", "channel-1", "user-1", null, "user-msg-1", url, "original");
    }
}
