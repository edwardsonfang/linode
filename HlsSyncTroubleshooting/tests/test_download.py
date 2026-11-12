import http.server
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import unittest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('download_hls', ROOT / 'download_hls.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
Downloader = module.Downloader


class DownloadTests(unittest.TestCase):
    def setUp(self):
        self.routes = {
            '/master.m3u8': b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvod/playlist?token=private\n',
            '/vod/playlist?token=private': b'#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="/key.bin?token=private"\n#EXT-X-MAP:URI="init.mp4"\n#EXTINF:1,\nsegment.ts?token=private\n#EXTINF:1,\nsegment.ts?token=private\n#EXT-X-ENDLIST\n',
            '/key.bin?token=private': b'0123456789abcdef',
            '/vod/init.mp4': b'init-bytes',
            '/vod/segment.ts?token=private': b'media-bytes',
        }
        routes = self.routes
        self.hits = []
        hits = self.hits
        required_headers = self.required_headers = {}

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                hits.append(self.path)
                if any(self.headers.get(k) != v for k, v in required_headers.items()):
                    self.send_error(403)
                    return
                body = routes.get(self.path)
                if body is None:
                    self.send_error(404)
                    return
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
        self.server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f'http://127.0.0.1:{self.server.server_port}/master.m3u8'
        self.temp = tempfile.TemporaryDirectory()
        self.output = Path(self.temp.name) / 'download'

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.temp.cleanup()

    def download(self, **kwargs):
        return Downloader(self.url, self.output, local_test=True, **kwargs).run()

    def test_recursive_graph_and_rewrite(self):
        result = self.download()
        self.assertTrue(result['complete'])
        self.assertEqual(len(result['objects']), 5)
        self.assertEqual(self.hits.count('/vod/segment.ts?token=private'), 1)
        by_url = {item['url']: item for item in result['objects']}
        for item in result['objects']:
            file = self.output / 'assets' / item['file']
            self.assertTrue(file.exists())
            if item['playlist']:
                text = file.read_text()
                self.assertNotIn('token=', text)
                self.assertNotIn('http:', text)
                for line in text.splitlines():
                    if line and not line.startswith('#'):
                        self.assertTrue((file.parent / line).exists())
                for match in module.URI_ATTRIBUTE.finditer(text.replace('\n', ',')):
                    self.assertTrue((file.parent / match['uri']).exists())
        self.assertEqual(len(by_url), 5)
        self.assertEqual((self.output / 'manifest.json').stat().st_mode & 0o777, 0o600)

    def test_missing_dependency_marks_incomplete(self):
        del self.routes['/vod/segment.ts?token=private']
        with self.assertRaises(ValueError): self.download()
        self.assertFalse(json.loads((self.output / 'manifest.json').read_text())['complete'])

    def test_rejects_external_host_before_request(self):
        self.routes['/master.m3u8'] = b'#EXTM3U\n#EXTINF:1,\nhttps://unapproved.invalid/secret.ts\n#EXT-X-ENDLIST\n'
        with self.assertRaises(ValueError): self.download()
        self.assertEqual(self.hits, ['/master.m3u8'])

    def test_limits_and_existing_output(self):
        with self.assertRaises(ValueError): self.download(max_files=1)
        with self.assertRaises(FileExistsError): self.download()

    def test_rejects_live_playlist(self):
        self.routes['/master.m3u8'] = b'#EXTM3U\n#EXTINF:1,\nsegment.ts\n'
        with self.assertRaises(ValueError): self.download()

    def test_cdn_headers_apply_to_all_dependencies(self):
        self.required_headers.update({'Referer': 'https://player.example.invalid/', 'User-Agent': 'Mozilla/5.0'})
        result = self.download(referer='https://player.example.invalid/', user_agent='Mozilla/5.0')
        self.assertTrue(result['complete'])
        self.assertEqual(len(result['objects']), 5)


if __name__ == '__main__': unittest.main()
