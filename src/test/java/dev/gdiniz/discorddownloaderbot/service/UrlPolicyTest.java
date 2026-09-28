package dev.gdiniz.discorddownloaderbot.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UrlPolicyTest {

    private static final Map<String, byte[]> DNS = Map.of(
            "youtube.com", new byte[]{(byte) 142, (byte) 250, 0, 1},
            "www.tiktok.com", new byte[]{23, 1, 2, 3},
            "rebind.example", new byte[]{10, 0, 0, 5},
            "metadata.example", new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 254});

    private final UrlPolicy policy = new UrlPolicy(host -> {
        var address = DNS.get(host);
        if (address == null) {
            if (Character.isDigit(host.charAt(0)) || host.startsWith("[")) return InetAddress.getAllByName(host);
            throw new UnknownHostException(host);
        }
        return new InetAddress[]{InetAddress.getByAddress(host, address)};
    });

    @ParameterizedTest
    @ValueSource(strings = {
            "https://youtube.com/watch?v=abc",
            "http://www.tiktok.com/@user/video/1",
            "https://8.8.8.8/video.mp4",
            "https://unknown-host.example/clip"
    })
    void publicUrlsAreAccepted(String url) {
        assertThat(policy.rejectionReason(url)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "--exec=touch /tmp/pwned",
            "-o /etc/passwd",
            "file:///etc/passwd",
            "ftp://youtube.com/x",
            "https://user:pass@youtube.com/x",
            "http://localhost:8080/actuator",
            "http://garage:3900/bucket/key",
            "http://consul-server.consul.svc.cluster.local:8500/v1/kv",
            "http://127.0.0.1/x",
            "http://10.1.2.3/x",
            "http://192.168.0.10/x",
            "http://172.16.0.1/x",
            "http://100.64.0.1/x",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/x",
            "http://[::ffff:127.0.0.1]/x",
            "http://[fd00::1]/x",
            "http://rebind.example/x",
            "http://metadata.example/x",
            "http://2130706433/x",
            "https://youtube.com/watch?v=a b",
            ""
    })
    void unsafeUrlsAreRejected(String url) {
        assertThat(policy.rejectionReason(url)).isPresent();
    }

    @Test
    void nullAndOversizedUrlsAreRejected() {
        assertThat(policy.rejectionReason(null)).isPresent();
        assertThat(policy.rejectionReason("https://youtube.com/" + "a".repeat(3000))).isPresent();
    }

    @Test
    void discordFormattingIsStripped() {
        assertThat(UrlPolicy.normalize("  <https://youtube.com/watch?v=abc>  ")).isEqualTo("https://youtube.com/watch?v=abc");
        assertThat(UrlPolicy.normalize("||https://youtube.com/watch?v=abc||")).isEqualTo("https://youtube.com/watch?v=abc");
        assertThat(UrlPolicy.normalize(null)).isNull();
    }
}
