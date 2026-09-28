package dev.gdiniz.discorddownloaderbot.service;

import dev.gdiniz.discorddownloaderbot.domain.DownloadJob;
import dev.gdiniz.discorddownloaderbot.domain.DownloadJobRepository;
import dev.gdiniz.discorddownloaderbot.domain.DownloadSourceRepository;
import dev.gdiniz.discorddownloaderbot.domain.DownloadStatus;
import dev.gdiniz.discorddownloaderbot.dto.ContentUnavailableException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadInterruptedException;
import dev.gdiniz.discorddownloaderbot.dto.DownloadRequest;
import dev.gdiniz.discorddownloaderbot.dto.DownloadResult;
import dev.gdiniz.discorddownloaderbot.dto.GatewayResponse;
import dev.gdiniz.discorddownloaderbot.dto.VideoProbe;
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

    private final YtDlpService ytDlp = mock(YtDlpService.class);
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
        orchestrator = orchestrator(10);
    }

    @Test
    void successfulDownloadRepliesWithAttachmentAndMarksJobDone() throws Exception {
        var file = downloaded("corr-ok", "clip.mp4", 2048);
        when(ytDlp.probe(any(), any())).thenReturn(VideoProbe.unknown());
        when(ytDlp.execute(any(), any(), any())).thenReturn(new DownloadResult(file, "Meu vídeo", 2048, "mp4"));
        when(s3.upload(any(), eq("guild-1"), eq("corr-ok"))).thenReturn("guild-1/corr-ok.mp4");

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
    void unexpectedErrorStillAnswersTheUserAndFailsTheJob() throws Exception {
        var file = downloaded("corr-boom", "clip.mp4", 2048);
        when(ytDlp.probe(any(), any())).thenReturn(VideoProbe.unknown());
        when(ytDlp.execute(any(), any(), any())).thenReturn(new DownloadResult(file, "clip", 2048, "mp4"));
        when(s3.upload(any(), any(), any())).thenThrow(new IllegalStateException("socket closed"));

        orchestrator.process(interaction("corr-boom", "https://youtube.com/watch?v=ok"));

        var last = sentResponses().getLast();
        assertThat(last.content()).isEqualTo(DownloadOrchestrator.MSG_UNEXPECTED);
        assertThat(last.finished()).isTrue();
        assertThat(savedStatuses).last().isEqualTo(DownloadStatus.FAILED);
    }

    @Test
    void internalUrlsAreRejectedBeforeAnyWork() {
        var policy = new UrlPolicy(host -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{10, 0, 0, 1})});
        var strict = new DownloadOrchestrator(ytDlp, ffmpeg, s3, sources, jobs, guildConfigs, responses,
                FakeTools.properties(tmp, tmp.resolve("work"), 10), new SimpleMeterRegistry(), policy);

        strict.process(interaction("corr-ssrf", "http://internal.example/admin"));

        assertThat(sentResponses()).singleElement()
                .extracting(GatewayResponse::content).isEqualTo(DownloadOrchestrator.MSG_INVALID_URL);
        verifyNoInteractions(ytDlp, s3);
        verify(jobs, never()).save(any());
    }

    @Test
    void redeliveredEventIsIgnored() {
        when(jobs.existsByCorrelationId("corr-dup")).thenReturn(true);

        orchestrator.process(interaction("corr-dup", "https://youtube.com/watch?v=ok"));

        verifyNoInteractions(ytDlp, responses);
        verify(jobs, never()).save(any());
    }

    @Test
    void unavailableContentGetsASpecificMessage() {
        when(ytDlp.probe(any(), any())).thenThrow(new ContentUnavailableException("private video"));

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
        when(ytDlp.probe(any(), any())).thenThrow(new ContentUnavailableException("removed"));

        orchestrator.process(messageCommand("corr-edit", "https://youtube.com/watch?v=gone"));

        var sent = sentResponses();
        assertThat(sent.getFirst().responseType()).isEqualTo("REPLY");
        assertThat(sent.getLast().responseType()).isEqualTo("UPDATE_MESSAGE");
    }

    @Test
    void processingTimeoutInterruptsTheWorkerAndTellsTheUser() {
        var quick = orchestrator(1);
        when(ytDlp.probe(any(), any())).thenReturn(VideoProbe.unknown());
        when(ytDlp.execute(any(), any(), any())).thenAnswer(inv -> {
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
        when(ytDlp.probe(any(), any())).thenReturn(VideoProbe.unknown());
        when(ytDlp.execute(any(), any(), any())).thenReturn(new DownloadResult(image, "photo", 30_000_000, "jpg"));
        when(ffmpeg.isImage(image)).thenReturn(true);

        orchestrator.process(interaction("corr-img", "https://youtube.com/watch?v=img"));

        verify(ffmpeg, never()).encodeToSize(any(), anyString(), anyLong());
        assertThat(sentResponses().getLast().content()).contains("não foi possível comprimir");
    }

    @Test
    void workDirectoryIsRemovedAfterProcessing() throws Exception {
        var file = downloaded("corr-clean", "clip.mp4", 10);
        var workDir = file.getParent();
        when(ytDlp.probe(any(), any())).thenReturn(VideoProbe.unknown());
        when(ytDlp.execute(any(), any(), any())).thenReturn(new DownloadResult(file, "clip", 10, "mp4"));
        when(s3.upload(any(), any(), any())).thenReturn("key");

        orchestrator.process(interaction("corr-clean", "https://youtube.com/watch?v=ok"));

        assertThat(workDir).doesNotExist();
    }

    @Test
    void shutdownWaitsForInFlightDownloads() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        when(ytDlp.probe(any(), any())).thenReturn(VideoProbe.unknown());
        doAnswer(inv -> {
            release.await(5, TimeUnit.SECONDS);
            throw new ContentUnavailableException("gone");
        }).when(ytDlp).execute(any(), any(), any());

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
        return new DownloadOrchestrator(ytDlp, ffmpeg, s3, sources, jobs, guildConfigs, responses,
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
