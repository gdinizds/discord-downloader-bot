package dev.gdiniz.discorddownloaderbot.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataUtilsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path tmp;

    @Test
    void unescapeHtmlHandlesCommonAndLatinEntitiesAndNumericCodes() {
        assertThat(MetadataUtils.unescapeHtml("Rock &amp; Roll")).isEqualTo("Rock & Roll");
        assertThat(MetadataUtils.unescapeHtml("Caf&eacute; com p&atilde;o de queijo &ccedil;")).isEqualTo("Café com pão de queijo ç");
        assertThat(MetadataUtils.unescapeHtml("Don&#39;t worry &quot;friend&quot;")).isEqualTo("Don't worry \"friend\"");
        assertThat(MetadataUtils.unescapeHtml("Unicode emoji: &#128514; and &#x1F600;")).isEqualTo("Unicode emoji: 😂 and 😀");
        assertThat(MetadataUtils.unescapeHtml("Space&nbsp;here")).isEqualTo("Space here");
        assertThat(MetadataUtils.unescapeHtml(null)).isNull();
    }

    @Test
    void truncateSafelyPreservesSurrogatePairsWithoutSplitting() {
        String withEmoji = "Olá mundo! 🤡💻";
        // 🤡 is high + low surrogate (2 chars)
        // Let's test truncating right in the middle of 🤡
        int emojiStart = withEmoji.indexOf("🤡");
        String truncated = MetadataUtils.truncateSafely(withEmoji, emojiStart + 1);

        assertThat(truncated).isEqualTo("Olá mundo! ");
        assertThat(Character.isHighSurrogate(truncated.charAt(truncated.length() - 1))).isFalse();
    }

    @Test
    void truncateWithEllipsisAppendsDotsSafely() {
        String text = "Texto muito longo com acentuação e emoji 🚀 para testar reticências";
        String truncated = MetadataUtils.truncateWithEllipsis(text, 25);

        assertThat(truncated).endsWith("...");
        assertThat(truncated.length()).isLessThanOrEqualTo(25);
    }

    @Test
    void extractTitleFromNodeExtractsUtf8SpecialCharactersAndEmojis() {
        var json = """
                {
                    "title": "Virou programador, deve tá rico agora 🤡💻",
                    "description": "Virou programador, deve tá rico agora 🤡💻 #viral #fyp #meme #dev #ia"
                }
                """;
        var node = JSON.readTree(json);
        String title = MetadataUtils.extractTitleFromNode(node);

        assertThat(title).isEqualTo("Virou programador, deve tá rico agora 🤡💻");
    }

    @Test
    void extractTitleFromNodeFallsBackToDescriptionIfTitleIsGeneric() {
        var json = """
                {
                    "title": "Instagram post by dev_humor",
                    "description": "Virou programador, deve tá rico agora 🤡💻 #viral #fyp #meme #dev #ia"
                }
                """;
        var node = JSON.readTree(json);
        String title = MetadataUtils.extractTitleFromNode(node);

        assertThat(title).isEqualTo("Virou programador, deve tá rico agora 🤡💻 #viral #fyp #meme #dev #ia");
    }

    @Test
    void extractTitleFromMetadataFindsInfoJsonWithAccentsAndEmojis() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("meta-test"));
        var file = dir.resolve("video.mp4");
        Files.write(file, new byte[100]);
        var info = dir.resolve("video.info.json");
        Files.writeString(info, "{\"title\": \"Ação &amp; Diversão 🤖🚀\", \"description\": \"Descrição completa com emojis 🤖🚀\"}");

        String title = MetadataUtils.extractTitleFromMetadata(dir, file, null);

        assertThat(title).isEqualTo("Ação & Diversão 🤖🚀");
    }

    @Test
    void extractTitleFromMetadataPrefersProbeInfoJsonIfPresent() throws Exception {
        var dir = Files.createDirectories(tmp.resolve("probe-meta-test"));
        var probe = dir.resolve("probe.info.json");
        Files.writeString(probe, "{\"title\": \"Título do Probe 💻\", \"description\": \"Desc\"}");

        String title = MetadataUtils.extractTitleFromMetadata(dir, null, probe);

        assertThat(title).isEqualTo("Título do Probe 💻");
    }

    @Test
    void sanitizeFileNamePreservesUtf8AndReplacesIllegalChars() {
        String clean = MetadataUtils.sanitizeFileName("Vídeo: Programação & IA/Meme? 🤡", "fallback");

        assertThat(clean).isEqualTo("Vídeo_ Programação & IA_Meme_ 🤡");
    }
}
