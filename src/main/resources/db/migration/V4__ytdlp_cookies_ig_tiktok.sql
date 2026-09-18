UPDATE downloader.download_sources
SET extra_args = extra_args || ARRAY['--cookies', '/app/cookies/cookies.txt']
WHERE host IN ('instagram.com', 'tiktok.com', 'vt.tiktok.com', 'vm.tiktok.com', 'm.tiktok.com');
