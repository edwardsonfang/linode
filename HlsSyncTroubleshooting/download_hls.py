#!/usr/bin/env python3
"""Download a static HLS dependency graph and rewrite it for object storage."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import urljoin, urlsplit, urldefrag
import requests

URI_ATTRIBUTE = re.compile(r'(?P<start>(?:^|[:,])URI=")(?P<uri>[^"]+)(?P<end>")')
PLAYLIST_TAGS = ('#EXT-X-MEDIA:', '#EXT-X-I-FRAME-STREAM-INF:')
UNSUPPORTED = ('#EXT-X-DEFINE:', '#EXT-X-PART:', '#EXT-X-PRELOAD-HINT:', '#EXT-X-RENDITION-REPORT:', '#EXT-X-CONTENT-STEERING:')


class DownloadError(ValueError):
    pass


class Downloader:
    def __init__(self, url, output, allowed_hosts=(), max_files=10000, max_bytes=10 * 1024**3, local_test=False, referer=None, user_agent=None):
        self.root = urldefrag(url)[0]
        self.output = Path(output)
        self.assets = self.output / 'assets'
        self.hosts = {urlsplit(url).hostname, *allowed_hosts}
        self.max_files, self.max_bytes = max_files, max_bytes
        self.total = 0
        self.local_test = local_test
        self.entries = {}
        self.session = requests.Session()
        if referer:
            self.session.headers['Referer'] = referer
        if user_agent:
            self.session.headers['User-Agent'] = user_agent

    def validate_url(self, url):
        u = urlsplit(url)
        local = self.local_test and u.scheme == 'http' and u.hostname in {'localhost', '127.0.0.1'}
        if (u.scheme != 'https' and not local) or u.username or u.password or u.hostname not in self.hosts:
            raise DownloadError('Blocked dependency/redirect host or scheme: ' + str(u.hostname) + '; explicitly allow required HTTPS hosts')

    def download(self, url, playlist=False):
        url = urldefrag(url)[0]
        if url in self.entries:
            return self.entries[url]['file']
        self.validate_url(url)
        if len(self.entries) >= self.max_files:
            raise DownloadError('File limit reached; download is incomplete')
        suffix = Path(urlsplit(url).path).suffix.lower()
        if not re.fullmatch(r'\.[a-z0-9]{1,8}', suffix):
            suffix = '.bin'
        name = 'index.m3u8' if url == self.root else hashlib.sha256(url.encode()).hexdigest() + ('.m3u8' if playlist else suffix)
        entry = self.entries[url] = {'url': url, 'file': name}
        current = url
        for _ in range(6):
            self.validate_url(current)
            response = self.session.get(current, stream=True, timeout=(10, 60), allow_redirects=False)
            if response.status_code in (301, 302, 303, 307, 308):
                location = response.headers.get('Location')
                response.close()
                if not location:
                    raise DownloadError('Redirect has no Location')
                current = urljoin(current, location)
                continue
            break
        else:
            raise DownloadError('Too many redirects')
        target = self.assets / name
        part = target.with_suffix(target.suffix + '.part')
        try:
            with response:
                if response.status_code != 200:
                    raise DownloadError(f'Source returned HTTP {response.status_code}; see private manifest for requested URLs')
                with part.open('wb') as out:
                    for chunk in response.iter_content(1024 * 1024):
                        self.total += len(chunk)
                        if self.total > self.max_bytes:
                            raise DownloadError('Byte limit reached; download is incomplete')
                        out.write(chunk)
            with part.open('rb') as source:
                magic = source.read(16)
            is_playlist = playlist or magic.startswith(b'#EXTM3U')
            if is_playlist:
                if part.stat().st_size > 16 * 1024 * 1024:
                    raise DownloadError('Playlist exceeds 16 MiB')
                text = part.read_text(encoding='utf-8-sig')
                if not text.startswith('#EXTM3U'):
                    raise DownloadError('Expected an HLS playlist')
                lines = text.splitlines()
                if any(line.startswith(UNSUPPORTED) for line in lines):
                    raise DownloadError('LL-HLS, variables or content steering require specialized handling')
                is_master = any(line.startswith(('#EXT-X-STREAM-INF:', *PLAYLIST_TAGS)) for line in lines)
                if not is_master and '#EXT-X-ENDLIST' not in lines:
                    raise DownloadError('Live/non-final playlist rejected; use a static VOD snapshot')
                if not name.endswith('.m3u8'):
                    name = hashlib.sha256(url.encode()).hexdigest() + '.m3u8'
                    entry['file'] = name
                    target = self.assets / name
                rewritten = []
                next_playlist = False
                for line in lines:
                    if line.startswith(('#EXT-X-KEY:', '#EXT-X-SESSION-KEY:')):
                        if 'METHOD=NONE' not in line and ('METHOD=AES-128,' not in line or ('KEYFORMAT=' in line and 'KEYFORMAT="identity"' not in line)):
                            raise DownloadError('Only identity AES-128 keys supported; DRM is not migrated')
                    if line and not line.startswith('#'):
                        line = self.download(urljoin(current, line.strip()), next_playlist)
                        next_playlist = False
                    else:
                        hint = line.startswith(PLAYLIST_TAGS)
                        line = URI_ATTRIBUTE.sub(lambda m: m['start'] + self.download(urljoin(current, m['uri']), hint) + m['end'], line)
                        if line.startswith('#EXT-X-STREAM-INF:'):
                            next_playlist = True
                    rewritten.append(line)
                part.write_text('\n'.join(rewritten) + '\n', encoding='utf-8')
            os.replace(part, target)
            entry.update(final_url=current, file=name, playlist=is_playlist, bytes=target.stat().st_size,
                         sha256=self.digest(target))
            return name
        finally:
            part.unlink(missing_ok=True)

    @staticmethod
    def digest(path):
        with path.open('rb') as source:
            return hashlib.file_digest(source, 'sha256').hexdigest()

    def run(self):
        # Never mix a partial run or user files with a new migration.
        self.output.mkdir(parents=True, exist_ok=False)
        self.output.chmod(0o700)
        self.assets.mkdir()
        try:
            self.download(self.root, True)
            result = {'complete': True, 'entrypoint': 'assets/index.m3u8', 'downloaded_bytes': self.total, 'objects': list(self.entries.values())}
        except Exception:
            self.write_manifest({'complete': False, 'objects': list(self.entries.values())})
            raise
        finally:
            self.session.close()
        self.write_manifest(result)
        return result

    def write_manifest(self, result):
        path = self.output / 'manifest.json'
        path.write_text(json.dumps(result, indent=2) + '\n')
        path.chmod(0o600)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('url')
    parser.add_argument('output', help='New output directory; must not exist')
    parser.add_argument('--allow-host', action='append', default=[])
    parser.add_argument('--max-files', type=int, default=10000)
    parser.add_argument('--max-bytes', type=int, default=10 * 1024**3)
    parser.add_argument('--referer', help='Referer required by the source CDN, if applicable')
    parser.add_argument('--user-agent', help='Source HTTP User-Agent, if applicable')
    args = parser.parse_args()
    try:
        result = Downloader(args.url, args.output, args.allow_host, args.max_files, args.max_bytes, referer=args.referer, user_agent=args.user_agent).run()
    except Exception as error:
        # Requests exception strings can contain signed source URLs. Do not log them.
        detail = str(error) if isinstance(error, DownloadError) else type(error).__name__
        print('Download incomplete: ' + detail + '. No upload should be attempted.', file=sys.stderr)
        return 1
    print(f"Downloaded {len(result['objects'])} objects; entrypoint: assets/index.m3u8")
    return 0


if __name__ == '__main__':
    sys.exit(main())
