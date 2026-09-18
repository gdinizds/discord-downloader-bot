UPDATE downloader.download_sources
SET extra_args = extra_args || ARRAY['--cookies', '/app/cookies/cookies.txt']
WHERE host IN ('youtube.com', 'youtu.be');
