"""Exercise real AWS SDK HTTP requests against a local S3-shaped test server.

Not a Linode emulator: it does not validate SigV4 or provider behavior.
"""
import hashlib
import http.server
import io
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]
JAVA = os.environ.get('JAVA', '/usr/bin/java')
CP = str(ROOT / 'target/classes') + ':' + (ROOT / 'target/classpath.txt').read_text().strip()
objects = {}


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_PUT(self):
        body = self.rfile.read(int(self.headers['Content-Length']))
        if 'aws-chunked' in self.headers.get('Content-Encoding', ''):
            stream = io.BytesIO(body)
            decoded = bytearray()
            while True:
                size = int(stream.readline().split(b';')[0].strip(), 16)
                if not size:
                    break
                decoded.extend(stream.read(size))
                assert stream.read(2) == b'\r\n'
            body = bytes(decoded)
        objects[urllib.parse.unquote(self.path.split('?')[0])] = (body, self.headers.get('Content-Type'))
        self.send_response(200)
        self.send_header('ETag', '"' + hashlib.md5(body).hexdigest() + '"')
        self.send_header('Content-Length', '0')
        self.end_headers()

    def do_GET(self):
        if self.path.startswith('/source/'):
            name = self.path[len('/source/'):]
            if name not in {'index.m3u8', 'segment000.ts', 'segment001.ts'}:
                self.send_error(404)
                return
            body = (ROOT / 'fixtures/hls' / name).read_bytes()
            self.send_response(200)
            self.send_header('Content-Type', 'application/octet-stream')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        obj = objects.get(urllib.parse.unquote(self.path.split('?')[0]))
        if obj is None:
            self.send_error(404)
            return
        body, mime = obj
        self.send_response(200)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
threading.Thread(target=server.serve_forever, daemon=True).start()
env = dict(os.environ, LINODE_ENDPOINT=f'http://127.0.0.1:{server.server_port}', LINODE_BUCKET='test-bucket', LINODE_REGION='us-ord-1', LINODE_ACCESS_KEY='local-test-only', LINODE_SECRET_KEY='local-test-only')
# Local requests must not traverse the cloud egress proxy.
for name in list(env):
    if name.lower() in {'http_proxy', 'https_proxy', 'all_proxy'}:
        env.pop(name)


def run(*args, success=True):
    result = subprocess.run([JAVA, '-cp', CP, 'repro.HlsSync', *map(str, args)], env=env, capture_output=True, text=True, timeout=60)
    if (result.returncode == 0) != success:
        raise AssertionError(result.stdout + result.stderr)
    return result


try:
    run(ROOT / 'fixtures/hls', 'hls-test')
    for file in (ROOT / 'fixtures/hls').iterdir():
        body, mime = objects['/test-bucket/hls-test/' + file.name]
        assert body == file.read_bytes()
        assert mime == ('application/vnd.apple.mpegurl' if file.suffix == '.m3u8' else 'video/mp2t')
    assert list(objects)[0].endswith('.ts'), 'Dependencies must upload before playlists'
    print('PASS: SDK uploads playlist and segment, preserves paths/MIME/bytes, verifies GET')
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        (root / 'index.m3u8').write_text('#EXTM3U\nmissing.ts\n')
        before = len(objects)
        run(root, 'missing-test', success=False)
        assert len(objects) == before
        (root / 'index.m3u8').write_text('#EXTM3U\nhttps://other-vendor.invalid/segment.ts?secret=redacted\n')
        result = run(root, 'external-test', success=False)
        assert 'secret=redacted' not in result.stderr
        assert len(objects) == before
        (root / 'index.m3u8').unlink()
        (root / 'sample.mp4').write_bytes(b'\x00\x00synthetic mp4 transfer fixture')
        run(root, 'mp4-test')
        assert objects['/test-bucket/mp4-test/sample.mp4'][1] == 'video/mp4'
        (root / 'media').mkdir()
        (root / 'keys').mkdir()
        (root / 'keys/key.bin').write_bytes(b'0123456789abcdef')
        (root / 'media/init.mp4').write_bytes(b'init fixture')
        (root / 'media/part 01.m4s').write_bytes(b'media fixture')
        (root / 'index.m3u8').write_text('#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nmedia/variant.m3u8\n')
        (root / 'media/variant.m3u8').write_text('#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="../keys/key.bin"\n#EXT-X-MAP:URI="init.mp4"\n#EXTINF:1,\npart%2001.m4s\n#EXT-X-ENDLIST\n')
        run(root, 'nested-test')
        assert objects['/test-bucket/nested-test/media/part 01.m4s'][0] == b'media fixture'
        (root / 'media/variant.m3u8').write_text('#EXTM3U\n../../outside.ts\n')
        before = len(objects)
        run(root, 'escape-test', success=False)
        assert len(objects) == before
    print('PASS: MP4 transfer; missing/external playlist dependencies fail before writes')
    print('PASS: nested master/media playlists, key/map URI attributes, escaped spaces; traversal rejected')
    spec = importlib.util.spec_from_file_location('download_hls', ROOT / 'download_hls.py')
    downloader = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(downloader)
    with tempfile.TemporaryDirectory() as directory:
        output = Path(directory) / 'migration'
        result = downloader.Downloader(env['LINODE_ENDPOINT'] + '/source/index.m3u8', output, local_test=True).run()
        assert result['complete'] and len(result['objects']) == 3
        run(output / 'assets', 'downloaded-test')
        for obj in result['objects']:
            assert objects['/test-bucket/downloaded-test/' + obj['file']][0] == (output / 'assets' / obj['file']).read_bytes()
        assert not any(k.endswith('manifest.json') for k in objects)
    print('PASS: source HTTP playlist -> recursive download/rewrite -> SDK PUT/GET verification')
finally:
    server.shutdown()
    server.server_close()
