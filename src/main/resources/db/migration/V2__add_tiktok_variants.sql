INSERT INTO downloader.download_sources (host, format_selector, extra_args) VALUES
    ('vt.tiktok.com', 'best[ext=mp4]/best', ARRAY['--no-playlist', '--no-check-certificates']),
    ('vm.tiktok.com', 'best[ext=mp4]/best', ARRAY['--no-playlist', '--no-check-certificates']),
    ('m.tiktok.com',  'best[ext=mp4]/best', ARRAY['--no-playlist', '--no-check-certificates']);
