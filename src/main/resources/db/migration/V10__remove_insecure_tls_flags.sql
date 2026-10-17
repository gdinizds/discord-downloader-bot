UPDATE downloader.download_sources
SET extra_args = array_remove(extra_args, '--no-check-certificates')
WHERE '--no-check-certificates' = ANY(extra_args);
