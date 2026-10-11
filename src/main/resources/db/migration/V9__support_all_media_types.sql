UPDATE downloader.download_sources
SET format_selector = 'bestvideo[vcodec^=avc1][height<=1080]+bestaudio[ext=m4a]/best[vcodec^=avc1][height<=1080]/bestvideo[ext=mp4][height<=1080]+bestaudio[ext=m4a]/best[ext=mp4]/bestvideo[height<=1080]+bestaudio/bestaudio/best'
WHERE host IN ('youtube.com', 'youtu.be', 'm.youtube.com');

UPDATE downloader.download_sources
SET format_selector = 'best[vcodec^=h264]/best[vcodec^=avc1]/best[ext=mp4]/bestaudio/best'
WHERE host IN ('tiktok.com', 'vt.tiktok.com', 'vm.tiktok.com', 'm.tiktok.com',
               'twitter.com', 'x.com', 'instagram.com');
