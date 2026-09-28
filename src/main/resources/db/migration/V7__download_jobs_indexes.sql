CREATE INDEX IF NOT EXISTS idx_download_jobs_correlation_id
    ON downloader.download_jobs (correlation_id);

CREATE INDEX IF NOT EXISTS idx_download_jobs_active
    ON downloader.download_jobs (created_at)
    WHERE status IN ('PENDING', 'DOWNLOADING', 'ENCODING', 'UPLOADING');
