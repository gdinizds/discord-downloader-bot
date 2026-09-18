CREATE SCHEMA IF NOT EXISTS downloader;

SET search_path TO downloader;

CREATE TABLE download_sources (
    id             BIGSERIAL PRIMARY KEY,
    host           VARCHAR(100) UNIQUE NOT NULL,
    format_selector TEXT NOT NULL,
    extra_args     TEXT[],
    requires_auth  BOOLEAN DEFAULT false,
    enabled        BOOLEAN DEFAULT true,
    created_at     TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE download_jobs (
    id              BIGSERIAL PRIMARY KEY,
    correlation_id  VARCHAR(36) NOT NULL,
    guild_id        VARCHAR(30) NOT NULL,
    user_id         VARCHAR(30) NOT NULL,
    url             TEXT NOT NULL,
    source_host     VARCHAR(100),
    qualidade       VARCHAR(20),
    status          VARCHAR(20) NOT NULL,
    file_size_bytes BIGINT,
    s3_key          TEXT,
    error_message   TEXT,
    created_at      TIMESTAMPTZ DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

INSERT INTO download_sources (host, format_selector, extra_args) VALUES
    ('youtube.com',   'bestvideo[ext=mp4][height<=1080]+bestaudio[ext=m4a]/best[ext=mp4]/best', ARRAY['--merge-output-format', 'mp4', '--no-playlist']),
    ('youtu.be',      'bestvideo[ext=mp4][height<=1080]+bestaudio[ext=m4a]/best[ext=mp4]/best', ARRAY['--merge-output-format', 'mp4', '--no-playlist']),
    ('twitter.com',   'best[ext=mp4]/best',                                                    ARRAY['--no-playlist']),
    ('x.com',         'best[ext=mp4]/best',                                                    ARRAY['--no-playlist']),
    ('instagram.com', 'best[ext=mp4]/best',                                                    ARRAY['--no-playlist', '--no-check-certificates']),
    ('tiktok.com',    'best[ext=mp4]/best',                                                    ARRAY['--no-playlist', '--no-check-certificates']);
