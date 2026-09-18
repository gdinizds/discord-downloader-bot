CREATE TABLE downloader.guild_configs (
    guild_id             VARCHAR(30) PRIMARY KEY,
    boost_tier           INT         NOT NULL DEFAULT 0,
    max_file_size_bytes  BIGINT      NOT NULL DEFAULT 26214400,
    max_bitrate_kbps     INT         NOT NULL DEFAULT 96,
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
