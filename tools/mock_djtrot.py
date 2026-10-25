#!/usr/bin/env python3
"""
로컬 개발용 DJ 트롯 목 서버 — 서버의 제공 목록이 비어 있을 때도 차량 앱의 재생·가사·노래 만들기 화면을 시험한다.

- 제공 목록(/catalog, /catalog/search, /catalog/songs/{id})만 흉내 낸다. 마스터트롯 공개 무대(--stage)의 곡과 가사 시각을
  `trot-song/1` 모양으로 바꿔 준다. 빼고 싶은 활동명은 --skip-artist로 준다.
- 그 곡들의 음원은 --audio-dir 의 MP3 중 하나를 곡 ID로 골라 내준다(Range 지원).
- 나머지(/config, /dj/brief, /pairings, /devices, /benefits, /songs …)는 DJ 트롯 서버(--upstream)로 그대로 전달한다.
- --fake-songs 초를 주면 노래 만들기(POST /songs)를 서버로 보내지 않는다. 그 초 동안 만드는 중으로 답하다가, 이 계정이
  이미 만든 노래 하나를 완성곡으로 돌려준다. 토큰을 쓰지 않고 주문·대기·완성 화면을 시험할 때 쓴다.

실행(에뮬레이터의 10.0.2.2는 맥의 127.0.0.1이다 — 기본은 이 맥에서만 열린다):
  uv run --no-project tools/mock_djtrot.py --audio-dir ~/Music/trot --upstream http://127.0.0.1:8787
  ./gradlew installDebug -Pdjtrot.api=http://10.0.2.2:8790/api/v1/dj-trot
"""
import argparse
import hashlib
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PREFIX = "/api/v1/dj-trot"
AUDIO = re.compile(r"^/api/v1/dj-trot/mock-audio/([0-9a-f-]{36})$")
SONG = re.compile(r"^/api/v1/dj-trot/catalog/songs/([0-9a-f-]{36})$")
FAKE = re.compile(r"^/api/v1/dj-trot/songs/(fake-[0-9a-f]{12})$")
STAGE = "https://miniapp.reopt.ai/api/v1/mastertrot"
FALLBACK_LABELS = {
    "style": {"ppongjjak": "신나는 뽕짝", "classic": "정통 트로트", "dance": "세미 트로트", "ballad": "트로트 발라드"},
    "topic": {"love": "사랑", "spouse": "여보 당신", "mother": "어머니", "hometown": "고향", "life": "인생", "friends": "친구", "cheer": "힘내라 응원가", "health": "건강 장수"},
    "voice": {"male": "남자 목소리", "female": "여자 목소리"},
}


def fetch_json(url, timeout=20):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"Accept": "application/json"}), timeout=timeout) as response:
        return json.load(response)


class Catalog:
    """운영 무대 곡을 처음 요청 때 한 번 받아 `trot-song/1`로 바꿔 둔다."""

    def __init__(self, upstream, stage, skip_artists):
        self.upstream = upstream
        self.stage = stage
        self.skip_artists = skip_artists
        self.lock = threading.Lock()
        self.songs = None

    def labels(self):
        try:
            choices = fetch_json(self.upstream + PREFIX + "/config", 5)["choices"]
            return {group: {item["id"]: item["label"] for item in items} for group, items in choices.items()}
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            return FALLBACK_LABELS

    def load(self):
        with self.lock:
            if self.songs is not None:
                return self.songs
            labels = self.labels()
            seen, picked = set(), []
            for sort in ("popular", "latest"):
                cursor = None
                for _ in range(6):
                    query = f"sort={sort}" + (f"&cursor={urllib.parse.quote(cursor)}" if cursor else "")
                    page = fetch_json(f"{self.stage}/stage?{query}")
                    for song in page.get("songs", []):
                        if song["id"] in seen or song.get("stageName") in self.skip_artists:
                            continue
                        seen.add(song["id"])
                        picked.append(song)
                    cursor = page.get("nextCursor")
                    if not cursor:
                        break

            def sync(song):
                try:
                    return fetch_json(f"{self.stage}/stage/songs/{song['id']}/lyric-sync", 10)
                except (OSError, ValueError):
                    return {"state": "missing", "lines": []}

            with ThreadPoolExecutor(8) as pool:
                syncs = list(pool.map(sync, picked))
            self.songs = [self.convert(song, sync, labels) for song, sync in zip(picked, syncs)]
            sys.stderr.write(f"catalog: {len(self.songs)} songs\n")
            return self.songs

    @staticmethod
    def convert(song, sync, labels):
        style = "ballad" if song.get("style", "").startswith("ballad") else song.get("style", "classic")
        voice = "male" if song.get("voice") in ("male", "baritone") else "female"
        lyrics = song.get("lyrics") or {}
        lines = (lyrics.get("verse") or []) + (lyrics.get("chorus") or [])
        ready = sync.get("state") == "ready"
        return {
            "schema": "trot-song/1",
            "id": song["id"],
            "title": song["title"],
            "artist": song.get("stageName", ""),
            "style": style,
            "topic": song.get("topic") or "life",
            "voice": voice,
            "labels": {
                "style": labels.get("style", {}).get(style, style),
                "topic": labels.get("topic", {}).get(song.get("topic"), song.get("topic") or ""),
                "voice": labels.get("voice", {}).get(voice, voice),
            },
            "durationSeconds": song.get("durationSeconds"),
            "audio": {"url": "{origin}" + PREFIX + "/mock-audio/" + song["id"], "contentType": "audio/mpeg"},
            "cover": None,
            "lyrics": {"title": song["title"], "lines": lines},
            "lyricSync": {"state": "ready" if ready else "missing", "lines": [{"text": l["text"], "start": l["start"], "end": l["end"]} for l in sync.get("lines", [])] if ready else []},
            "provenance": {"kind": "house", "createdAt": song.get("publishedAt", "")},
        }


def passed(headers):
    return {name: headers[name] for name in ("Content-Range", "Accept-Ranges") if headers.get(name)}


def make_handler(upstream, files, catalog, fake_seconds):
    # 가짜 주문 — id → (맡긴 때, 사연)
    orders = {}

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            sys.stderr.write(f"{self.command} {fmt % args}\n")

        def origin(self):
            return "http://" + self.headers.get("Host", "127.0.0.1:8790")

        def songs(self):
            text = json.dumps(catalog.load(), ensure_ascii=False).replace("{origin}", self.origin())
            return json.loads(text)

        def do_HEAD(self):
            self.do_GET(head=True)

        def do_GET(self, head=False):
            parsed = urllib.parse.urlparse(self.path)
            path = parsed.path
            match = AUDIO.match(path)
            if match:
                return self.audio(match.group(1), head)
            if path == PREFIX + "/catalog":
                songs = self.songs()
                by_style = {}
                for song in songs:
                    by_style.setdefault(song["style"], []).append(song)
                shelves = [
                    {"id": "picks", "title": "DJ 트롯 추천", "description": "오늘의 트로트", "songs": songs[:20]},
                    {"id": "latest", "title": "새로 들어온 노래", "description": "최근에 만든 노래", "songs": sorted(songs, key=lambda s: s["provenance"]["createdAt"], reverse=True)[:20]},
                ] + [{"id": "style:" + style, "title": items[0]["labels"]["style"], "description": "", "songs": items} for style, items in by_style.items()]
                return self.json({"generatedAt": "mock", "shelves": shelves}, head)
            if path == PREFIX + "/catalog/search":
                words = urllib.parse.parse_qs(parsed.query).get("q", [""])[0].lower().split()
                songs = self.songs()
                if words:
                    haystack = lambda s: " ".join([s["title"], s["artist"], s["labels"]["style"], s["labels"]["topic"], *s["lyrics"]["lines"]]).lower()
                    songs = [s for s in songs if all(w in haystack(s) for w in words)]
                return self.json({"songs": songs[:50]}, head)
            match = SONG.match(path)
            if match:
                found = next((s for s in self.songs() if s["id"] == match.group(1)), None)
                if found is None:
                    return self.json({"error": {"code": "SONG_NOT_FOUND", "message": "노래를 찾지 못했어요."}}, head, 404)
                return self.json(found, head)
            match = FAKE.match(path)
            if match and match.group(1) in orders:
                return self.json(self.fake_state(match.group(1)), head)
            return self.proxy("GET", head)

        def do_POST(self):
            if fake_seconds and urllib.parse.urlparse(self.path).path == PREFIX + "/songs":
                return self.fake_order()
            self.proxy("POST")

        def fake_order(self):
            length = int(self.headers.get("Content-Length") or 0)
            body = json.loads(self.rfile.read(length) or b"{}")
            song_id = "fake-" + hashlib.sha256(body.get("requestKey", "").encode()).hexdigest()[:12]
            orders.setdefault(song_id, (time.monotonic(), {k: body.get(k, "") for k in ("topic", "style", "voice", "story")}))
            sys.stderr.write(f"fake order {song_id}: {orders[song_id][1]['story']}\n")
            return self.json(self.fake_state(song_id), status=201)

        def fake_state(self, song_id):
            started, brief = orders[song_id]
            waited = time.monotonic() - started
            state = "queued" if waited < 3 else "running" if waited < fake_seconds else "ready"
            value = {"id": song_id, "state": state, "brief": brief}
            if state == "ready":
                made = self.made_song()
                if made is None:
                    return {**value, "state": "failed", "errorCode": "MOCK_NO_SONG"}
                value["song"] = made
            return value

        def made_song(self):
            """이 계정이 이미 만든 노래 중 가장 최근 것 — 차가 보낸 로그인 토큰으로 서버에 묻는다."""
            headers = {k: v for k, v in self.headers.items() if k.lower() in ("authorization", "host")}
            try:
                with urllib.request.urlopen(urllib.request.Request(upstream + PREFIX + "/songs", headers=headers), timeout=10) as response:
                    page = json.loads(response.read())
            except (urllib.error.URLError, ValueError):
                return None
            return next((s["song"] for s in page.get("songs", []) if s.get("state") == "ready" and s.get("song")), None)

        def do_DELETE(self):
            self.proxy("DELETE")

        def proxy(self, method, head=False):
            if not self.path.startswith(PREFIX):
                return self.json({"error": {"code": "NOT_FOUND", "message": "mock"}}, head, 404)
            length = int(self.headers.get("Content-Length") or 0)
            data = self.rfile.read(length) if length else None
            # Host까지 넘겨야 서버가 만드는 절대 주소가 에뮬레이터(10.0.2.2)에서 닿는다.
            headers = {k: v for k, v in self.headers.items() if k.lower() in ("accept", "authorization", "content-type", "host", "range")}
            request = urllib.request.Request(upstream + self.path, data=data, headers=headers, method=method)
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    return self.reply(response.status, response.read(), response.headers.get("Content-Type", "application/json"), head, passed(response.headers))
            except urllib.error.HTTPError as error:
                return self.reply(error.code, error.read(), error.headers.get("Content-Type", "application/json"), head)
            except urllib.error.URLError:
                return self.json({"error": {"code": "UPSTREAM_DOWN", "message": "DJ 트롯 서버(" + upstream + ")에 닿지 않아요."}}, head, 502)

        def json(self, value, head=False, status=200):
            return self.reply(status, json.dumps(value, ensure_ascii=False).encode(), "application/json; charset=utf-8", head)

        def audio(self, song_id, head):
            name = files[int(hashlib.sha256(song_id.encode()).hexdigest(), 16) % len(files)]
            size = os.path.getsize(name)
            start, end, status = 0, size - 1, 200
            ranged = re.match(r"bytes=(\d*)-(\d*)", self.headers.get("Range", ""))
            if ranged and (ranged.group(1) or ranged.group(2)):
                if ranged.group(1):
                    start = int(ranged.group(1))
                    end = int(ranged.group(2)) if ranged.group(2) else size - 1
                else:
                    start = max(0, size - int(ranged.group(2)))
                end = min(end, size - 1)
                status = 206
            self.send_response(status)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Length", str(end - start + 1))
            if status == 206:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            if head:
                return
            with open(name, "rb") as source:
                source.seek(start)
                remaining = end - start + 1
                while remaining > 0:
                    chunk = source.read(min(65536, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)

        def reply(self, status, body, content_type, head=False, extra=None):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            for name, value in (extra or {}).items():
                self.send_header(name, value)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            if not head:
                self.wfile.write(body)

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--audio-dir", required=True, help="MP3 파일이 있는 폴더")
    parser.add_argument("--upstream", default="http://127.0.0.1:8787", help="나머지 요청을 넘길 DJ 트롯 서버")
    parser.add_argument("--stage", default=STAGE, help="곡 목록을 가져올 마스터트롯 공개 무대 API")
    parser.add_argument("--skip-artist", action="append", default=[], metavar="NAME", help="제공 목록에서 뺄 활동명(여러 번 줄 수 있다)")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8790)
    parser.add_argument("--fake-songs", type=int, default=0, metavar="SECONDS", help="노래 만들기를 서버로 보내지 않고 이 초 뒤 완성으로 답한다(0이면 끔)")
    args = parser.parse_args()
    files = sorted(os.path.join(args.audio_dir, f) for f in os.listdir(args.audio_dir) if f.endswith(".mp3"))
    if not files:
        sys.exit("MP3 파일이 없어요: " + args.audio_dir)
    server = ThreadingHTTPServer((args.host, args.port), make_handler(args.upstream.rstrip("/"), files, Catalog(args.upstream.rstrip("/"), args.stage.rstrip("/"), set(args.skip_artist)), args.fake_songs))
    print(f"DJ 트롯 목 서버 http://{args.host}:{args.port}{PREFIX} → 제공 목록·음원은 흉내, 나머지는 {args.upstream}", flush=True)
    if args.fake_songs:
        print(f"노래 만들기는 흉내: 서버로 보내지 않고 {args.fake_songs}초 뒤 이미 만든 노래로 완성", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
