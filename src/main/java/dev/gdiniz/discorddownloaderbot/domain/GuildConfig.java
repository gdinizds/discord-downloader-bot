package dev.gdiniz.discorddownloaderbot.domain;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "guild_configs", schema = "downloader")
public class GuildConfig {

    @Id
    @Column(length = 30)
    private String guildId;

    @Column(nullable = false)
    private int boostTier;

    @Column(nullable = false)
    private long maxFileSizeBytes;

    @Column(nullable = false)
    private int maxBitrateKbps;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    public static GuildConfig from(String guildId, int boostTier, long maxFileSizeBytes, int maxBitrateKbps) {
        var c = new GuildConfig();
        c.guildId = guildId;
        c.boostTier = boostTier;
        c.maxFileSizeBytes = maxFileSizeBytes;
        c.maxBitrateKbps = maxBitrateKbps;
        c.updatedAt = OffsetDateTime.now();
        return c;
    }

    public String getGuildId()          { return guildId; }
    public int getBoostTier()           { return boostTier; }
    public long getMaxFileSizeBytes()   { return maxFileSizeBytes; }
    public int getMaxBitrateKbps()      { return maxBitrateKbps; }
    public OffsetDateTime getUpdatedAt(){ return updatedAt; }
}
