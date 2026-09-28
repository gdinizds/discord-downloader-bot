UPDATE downloader.download_sources
SET format_selector = 'bestvideo[vcodec^=avc1][height<=1080]+bestaudio[ext=m4a]/best[vcodec^=avc1][height<=1080]/bestvideo[ext=mp4][height<=1080]+bestaudio[ext=m4a]/best[ext=mp4]/best'
WHERE host IN ('youtube.com', 'youtu.be');

INSERT INTO downloader.download_sources (host, format_selector, extra_args)
SELECT 'm.youtube.com', format_selector, extra_args
FROM downloader.download_sources
WHERE host = 'youtube.com'
ON CONFLICT (host) DO NOTHING;

UPDATE downloader.download_sources
SET format_selector = 'best[vcodec^=h264]/best[vcodec^=avc1]/best[ext=mp4]/best'
WHERE host IN ('tiktok.com', 'vt.tiktok.com', 'vm.tiktok.com', 'm.tiktok.com',
               'twitter.com', 'x.com', 'instagram.com');
