package dev.gdiniz.discorddownloaderbot.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MetadataUtils {

    private static final Logger log = LoggerFactory.getLogger(MetadataUtils.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Pattern NUMERIC_DECIMAL = Pattern.compile("&#([0-9]{1,7});");
    private static final Pattern NUMERIC_HEX = Pattern.compile("&#[xX]([0-9a-fA-F]{1,6});");

    private static final Map<String, String> NAMED_ENTITIES = Map.ofEntries(
            Map.entry("&amp;", "&"),
            Map.entry("&lt;", "<"),
            Map.entry("&gt;", ">"),
            Map.entry("&quot;", "\""),
            Map.entry("&apos;", "'"),
            Map.entry("&#39;", "'"),
            Map.entry("&nbsp;", " "),
            Map.entry("&copy;", "©"),
            Map.entry("&reg;", "®"),
            Map.entry("&aacute;", "á"),
            Map.entry("&Aacute;", "Á"),
            Map.entry("&eacute;", "é"),
            Map.entry("&Eacute;", "É"),
            Map.entry("&iacute;", "í"),
            Map.entry("&Iacute;", "Í"),
            Map.entry("&oacute;", "ó"),
            Map.entry("&Oacute;", "Ó"),
            Map.entry("&uacute;", "ú"),
            Map.entry("&Uacute;", "Ú"),
            Map.entry("&atilde;", "ã"),
            Map.entry("&Atilde;", "Ã"),
            Map.entry("&otilde;", "õ"),
            Map.entry("&Otilde;", "Õ"),
            Map.entry("&ccedil;", "ç"),
            Map.entry("&Ccedil;", "Ç"),
            Map.entry("&acirc;", "â"),
            Map.entry("&Acirc;", "Â"),
            Map.entry("&ecirc;", "ê"),
            Map.entry("&Ecirc;", "Ê"),
            Map.entry("&icirc;", "î"),
            Map.entry("&Icirc;", "Î"),
            Map.entry("&ocirc;", "ô"),
            Map.entry("&Ocirc;", "Ô"),
            Map.entry("&ucirc;", "û"),
            Map.entry("&Ucirc;", "Û"),
            Map.entry("&agrave;", "à"),
            Map.entry("&Agrave;", "À"),
            Map.entry("&uuml;", "ü"),
            Map.entry("&Uuml;", "Ü")
    );

    private MetadataUtils() {}

    public static String extractTitleFromMetadata(Path outputDir, Path primaryFile, Path probeInfoJson) {
        if (probeInfoJson != null && Files.exists(probeInfoJson)) {
            String title = readTitleFromJson(probeInfoJson);
            if (title != null && !title.isBlank()) {
                return title;
            }
        }

        if (primaryFile != null) {
            Path directInfoSidecar = Path.of(primaryFile + ".info.json");
            if (Files.exists(directInfoSidecar)) {
                String title = readTitleFromJson(directInfoSidecar);
                if (title != null && !title.isBlank()) return title;
            }

            Path directSidecar = Path.of(primaryFile + ".json");
            if (Files.exists(directSidecar)) {
                String title = readTitleFromJson(directSidecar);
                if (title != null && !title.isBlank()) return title;
            }

            String primaryStr = primaryFile.toString();
            int dot = primaryStr.lastIndexOf('.');
            if (dot > 0) {
                Path strippedInfo = Path.of(primaryStr.substring(0, dot) + ".info.json");
                if (Files.exists(strippedInfo)) {
                    String title = readTitleFromJson(strippedInfo);
                    if (title != null && !title.isBlank()) return title;
                }

                Path strippedSidecar = Path.of(primaryStr.substring(0, dot) + ".json");
                if (Files.exists(strippedSidecar)) {
                    String title = readTitleFromJson(strippedSidecar);
                    if (title != null && !title.isBlank()) return title;
                }
            }
        }

        if (outputDir != null && Files.exists(outputDir)) {
            Path infoJson = outputDir.resolve("info.json");
            if (Files.exists(infoJson)) {
                String title = readTitleFromJson(infoJson);
                if (title != null && !title.isBlank()) {
                    return title;
                }
            }

            try (var stream = Files.list(outputDir)) {
                var jsonFiles = stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".json"))
                        .toList();
                for (Path p : jsonFiles) {
                    if (p.equals(infoJson) || (probeInfoJson != null && p.equals(probeInfoJson))) continue;
                    String title = readTitleFromJson(p);
                    if (title != null && !title.isBlank()) {
                        return title;
                    }
                }
            } catch (IOException e) {
                log.warn("Failed scanning directory for metadata json: {}", e.getMessage());
            }
        }

        return null;
    }

    public static String readTitleFromJson(Path jsonPath) {
        if (jsonPath == null || !Files.exists(jsonPath)) return null;
        try (var in = Files.newInputStream(jsonPath)) {
            JsonNode root = JSON.readTree(in);
            return extractTitleFromNode(root);
        } catch (Exception e) {
            log.debug("Could not read title from {}: {}", jsonPath, e.getMessage());
            return null;
        }
    }

    public static String extractTitleFromNode(JsonNode root) {
        if (root == null) return null;
        if (root.isArray() && !root.isEmpty()) {
            root = root.get(0);
        }

        String[] candidateKeys = {"content", "title", "description", "caption", "text", "status_text"};
        for (String key : candidateKeys) {
            JsonNode node = root.path(key);
            if (node != null && node.isTextual()) {
                String val = node.asText().strip();
                if (!val.isBlank()) {
                    if (key.equals("title") && isGenericTitle(val)) {
                        JsonNode descNode = root.path("description");
                        if (descNode != null && descNode.isTextual()) {
                            String descVal = descNode.asText().strip();
                            if (!descVal.isBlank() && !isGenericTitle(descVal)) {
                                val = descVal;
                            }
                        }
                    }
                    val = unescapeHtml(val);
                    if (val.length() > 2000) {
                        val = truncateWithEllipsis(val, 2000);
                    }
                    return val;
                }
            }
        }
        return null;
    }

    private static boolean isGenericTitle(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.equals("clip")
                || lower.startsWith("instagram post by")
                || lower.startsWith("post by")
                || lower.startsWith("video by");
    }

    public static String unescapeHtml(String text) {
        if (text == null || text.indexOf('&') == -1) return text;
        String result = text;
        for (var entry : NAMED_ENTITIES.entrySet()) {
            if (result.contains(entry.getKey())) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
        }
        if (result.contains("&#")) {
            Matcher m = NUMERIC_DECIMAL.matcher(result);
            result = m.replaceAll(mr -> {
                try {
                    int codePoint = Integer.parseInt(mr.group(1));
                    if (Character.isValidCodePoint(codePoint)) {
                        return Matcher.quoteReplacement(Character.toString(codePoint));
                    }
                } catch (Exception ignored) {}
                return mr.group(0);
            });
            m = NUMERIC_HEX.matcher(result);
            result = m.replaceAll(mr -> {
                try {
                    int codePoint = Integer.parseInt(mr.group(1), 16);
                    if (Character.isValidCodePoint(codePoint)) {
                        return Matcher.quoteReplacement(Character.toString(codePoint));
                    }
                } catch (Exception ignored) {}
                return mr.group(0);
            });
        }
        return result;
    }

    public static String truncateSafely(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        int end = maxLength;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    public static String truncateWithEllipsis(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text;
        }
        if (maxLength <= 3) {
            return "...".substring(0, maxLength);
        }
        int end = maxLength - 3;
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "...";
    }

    public static String sanitizeFileName(String title, String fallback) {
        if (title == null || title.isBlank()) {
            return fallback;
        }
        String clean = title.replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", " ")
                .strip();
        if (clean.length() > 80) {
            clean = truncateSafely(clean, 80).strip();
        }
        return clean.isBlank() ? fallback : clean;
    }
}
