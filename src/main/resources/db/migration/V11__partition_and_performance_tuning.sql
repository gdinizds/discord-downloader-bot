SET search_path TO downloader;

CREATE SCHEMA IF NOT EXISTS partman;
CREATE EXTENSION IF NOT EXISTS pg_partman SCHEMA partman;

ALTER TABLE downloader.download_jobs RENAME TO download_jobs_old;

DROP INDEX IF EXISTS downloader.idx_download_jobs_correlation_id;
DROP INDEX IF EXISTS downloader.idx_download_jobs_active;

CREATE TABLE downloader.download_jobs (
    id              BIGINT GENERATED ALWAYS AS IDENTITY,
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
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ,
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

CREATE INDEX idx_download_jobs_correlation_id
    ON downloader.download_jobs (correlation_id);

CREATE INDEX idx_download_jobs_active
    ON downloader.download_jobs (status, created_at)
    WHERE status IN ('PENDING', 'DOWNLOADING', 'ENCODING', 'UPLOADING');

SELECT partman.create_parent(
    p_parent_table  => 'downloader.download_jobs',
    p_control       => 'created_at',
    p_interval      => '1 day',
    p_premake       => 3,
    p_default_table => true
);

ALTER TABLE partman.template_downloader_download_jobs SET (fillfactor = 85);

UPDATE partman.part_config
   SET retention = '30 days',
       retention_keep_table = false,
       retention_keep_index = false,
       infinite_time_partitions = true
 WHERE parent_table = 'downloader.download_jobs';

INSERT INTO downloader.download_jobs (id, correlation_id, guild_id, user_id, url, source_host, qualidade, status, file_size_bytes, s3_key, error_message, created_at, completed_at)
OVERRIDING SYSTEM VALUE
SELECT id, correlation_id, guild_id, user_id, url, source_host, qualidade, status, file_size_bytes, s3_key, error_message, created_at, completed_at
FROM downloader.download_jobs_old;

SELECT setval(pg_get_serial_sequence('downloader.download_jobs', 'id'), COALESCE(MAX(id), 1), true)
  FROM downloader.download_jobs;

DROP TABLE downloader.download_jobs_old;

ALTER TABLE downloader.guild_configs
    ADD CONSTRAINT chk_guild_configs_boost_tier CHECK (boost_tier BETWEEN 0 AND 3),
    ADD CONSTRAINT chk_guild_configs_file_size CHECK (max_file_size_bytes > 0),
    ADD CONSTRAINT chk_guild_configs_bitrate CHECK (max_bitrate_kbps > 0);
