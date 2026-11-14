#!/usr/bin/env python3
"""
차량 신호 시험 도구 — 에뮬레이터가 주지 않는 차량·내비 신호와 말로 넣는 사연을 흉내 내 DJ 멘트를 시험한다.

디버그 빌드의 수신기(`debug/TriggerReceiver`)에 adb 브로드캐스트를 보내고, 그 신호로 기기 DJ가 할 말을 돌려받아 찍는다.
덧씌운 값은 실제 신호 위에 남고, 앱 프로세스가 끝나면 사라진다. 파이썬 표준 라이브러리와 adb만 쓴다.

  uv run --no-project tools/trigger.py keys                                   # 쓸 수 있는 키
  uv run --no-project tools/trigger.py set gear=DRIVE rain=on jam=12 jam_road=경부고속도로
  uv run --no-project tools/trigger.py set rain=-                             # 그 키만 실제 값으로
  uv run --no-project tools/trigger.py depart 강릉역 --min 150 --km 210       # 길 안내 시작
  uv run --no-project tools/trigger.py waypoint 횡성휴게소
  uv run --no-project tools/trigger.py arrive 강릉역 --min 150 --km 210
  uv run --no-project tools/trigger.py say "어머니 생신이라 고향 가는 길이에요"  # 마이크 대신 사연 넣기
  uv run --no-project tools/trigger.py show                                   # 지금 신호로 DJ가 할 말
  uv run --no-project tools/trigger.py clear                                  # 모두 실제 값으로
  uv run --no-project tools/trigger.py scenario trip --step 20 --next         # 정해 둔 길을 차례로
  uv run --no-project tools/trigger.py watch                                  # DJ가 실제로 하는 말을 나오는 대로
  uv run --no-project tools/trigger.py media play                             # 재생 키(play pause next previous)

- 곡 소개 멘트는 곡이 바뀔 때 나온다. `--next`를 주면 신호를 바꾼 뒤 다음 곡으로 넘기고, DJ가 실제로 한 말을 기다려 찍는다.
- 출발·경유지·도착 한마디는 노래가 나오는 중이고 DJ 멘트가 켜져 있을 때만 나온다. 아니면 결과에 ⚠️로 알린다.
- 서버가 닿으면 서버 DJ 브리프가 먼저다. 여기서 보여 주는 말은 기기 멘트(서버가 닿지 않을 때 나오는 말)이고,
  정체·비·동승자 같은 값은 서버로 가지 않는다. 결과의 "서버로 가는 맥락"에서 확인한다.
- `say`는 앱이 평소 하듯 DJ 브리프(`/dj/brief`)를 한 번 부른다. 노래 만들기(`POST /songs`)는 부르지 않는다.
"""
import argparse
import os
import shlex
import subprocess
import sys
import time

ACTION = "ai.reopt.djtrot.debug.TRIGGER"
RECEIVER = "ai.reopt.djtrot/.debug.TriggerReceiver"
MEDIA_KEYS = {"play": "126", "pause": "127", "next": "87", "previous": "88"}
# 🔑 앱의 `Staging.LAST`와 같은 글이다. 한쪽을 바꾸면 함께 바꾼다.
LAST = "방금 DJ가 한 말: "

# 한 단계 = (설명, 명령, 값). 값의 키는 수신기가 받는 extra 이름이다.
SCENARIOS = {
    "trip": ("고속도로로 멀리 가는 길 — 출발, 크루즈, 터널, 정체, 휴게소, 도착", [
        ("서울에서 강릉으로 출발", "depart", {"name": "강릉역", "min": "150", "km": "210"}),
        ("고속도로에 올라 크루즈를 켠다", "set", {"gear": "DRIVE", "address": "경기도 광주시 초월읍", "cruise": "on", "dest_min": "130", "dest_km": "190"}),
        ("곧 터널", "set", {"ahead": "TUNNEL"}),
        ("터널을 지나니 길이 막힌다", "set", {"ahead": "-", "cruise": "off", "jam": "18", "jam_road": "영동고속도로", "dest_min": "95"}),
        ("정체가 풀리고 두 시간을 넘겨 달렸다, 곧 휴게소", "set", {"jam": "-", "jam_road": "-", "trip": "125", "ahead": "REST_AREA", "address": "강원특별자치도 횡성군 안흥면"}),
        ("휴게소에 들른다", "waypoint", {"name": "횡성휴게소"}),
        ("다시 달려 거의 다 왔다", "set", {"ahead": "-", "trip": "170", "address": "강원특별자치도 강릉시 교동", "dest_min": "3", "dest_km": "1.5"}),
        ("도착", "arrive", {"name": "강릉역", "min": "175", "km": "212"}),
    ]),
    "home": ("비 오는 저녁, 동승자와 집으로 — 출발, 비, 사고 정체, 어린이보호구역, 도착", [
        ("회사에서 집으로 출발", "depart", {"name": "우리 집", "min": "35", "km": "14", "kind": "HOME"}),
        ("비가 오고 옆자리에 누가 탔다", "set", {"gear": "DRIVE", "address": "서울특별시 강남구 역삼동", "rain": "on", "company": "on"}),
        ("가는 길에 사고", "set", {"jam": "9", "jam_road": "올림픽대로", "accident": "on", "dest_min": "41"}),
        ("사고 구간을 지났다", "set", {"jam": "-", "jam_road": "-", "accident": "-", "dest_min": "12"}),
        ("집 앞 어린이보호구역", "set", {"school": "on", "address": "경기도 성남시 수정구 태평동", "dest_min": "5"}),
        ("도착", "arrive", {"name": "우리 집", "min": "38", "km": "14"}),
    ]),
    "wander": ("목적지 없이 달리다 서서 충전 — 안개, 방향지시등, 충전", [
        ("목적지 없이 달린다", "set", {"gear": "DRIVE", "dest": "none", "free": "on", "address": "강원특별자치도 양양군 현남면"}),
        ("안개가 낀다", "set", {"fog": "on"}),
        ("방향지시등을 켠다(목소리를 미룬다)", "set", {"signal": "on"}),
        ("방향지시등을 끈다", "set", {"signal": "-", "fog": "-"}),
        ("세우고 충전구를 연다", "set", {"gear": "PARK", "free": "-", "port": "on", "battery": "34"}),
    ]),
}


def adb(args, serial):
    command = ["adb"] + (["-s", serial] if serial else []) + args
    try:
        return subprocess.run(command, check=False, capture_output=True, text=True, timeout=20)
    except FileNotFoundError:
        sys.exit("adb를 찾지 못했습니다. Android SDK platform-tools를 PATH에 넣어 주세요.")
    except subprocess.TimeoutExpired:
        sys.exit("adb가 답하지 않습니다. 에뮬레이터가 켜져 있는지 확인해 주세요.")


def send(command, values, serial):
    """수신기에 명령을 보내고 결과 글을 돌려준다. 실패하면 끝낸다."""
    # 🔑 adb shell은 인자를 한 줄로 이어 기기 셸에 넘긴다 — 띄어쓰기와 한글이 든 값은 따옴표로 감싸야 한다.
    words = ["am", "broadcast", "--user", "current", "--include-stopped-packages", "-a", ACTION, "-n", RECEIVER, "--es", "cmd", command]
    for key, value in values.items():
        words += ["--es", key, value]
    result = adb(["shell", " ".join(shlex.quote(word) for word in words)], serial)
    output = result.stdout + result.stderr
    if result.returncode != 0:
        sys.exit(output.strip() or "adb 명령이 실패했습니다.")
    marker = 'data="'
    if marker not in output:
        sys.exit(
            "앱이 답하지 않았습니다. 디버그 빌드가 설치되어 있는지 확인해 주세요(./gradlew :app:installDebug).\n" + output.strip()
        )
    text = output[output.index(marker) + len(marker):].rstrip()
    text = text.removesuffix('"')
    if text.startswith("오류:"):
        sys.exit(text)
    return text


def last_line(text):
    """결과 글에서 DJ가 실제로 마지막에 한 말(시각 포함)을 꺼낸다."""
    for line in text.splitlines():
        if line.startswith(LAST):
            return line[len(LAST):]
    return ""


def media(key, serial):
    adb(["shell", "input", "keyevent", MEDIA_KEYS[key]], serial)


def next_song(serial, wait):
    """다음 곡으로 넘기고, DJ가 실제로 곡 소개를 할 때까지 기다려 그 말을 찍는다."""
    before = last_line(send("show", {}, serial))
    media("next", serial)
    deadline = time.monotonic() + wait
    while time.monotonic() < deadline:
        time.sleep(1)
        said = last_line(send("show", {}, serial))
        if said != before:
            print(f"▶ DJ가 실제로 한 말: {said}")
            return
    print(f"▶ {wait:g}초 동안 새 멘트가 없었습니다. 노래가 나오는 중인지, DJ 멘트가 켜져 있는지 확인해 주세요(같은 말을 되풀이하면 새 멘트로 치지 않습니다).")


def watch(serial):
    """DJ가 실제로 하는 말을 나오는 대로 찍는다. Ctrl-C로 끝낸다."""
    said = last_line(send("show", {}, serial))
    print(f"지금까지: {said}\nDJ가 말할 때마다 찍습니다. Ctrl-C로 끝냅니다.")
    try:
        while True:
            time.sleep(1)
            now = last_line(send("show", {}, serial))
            if now != said:
                said = now
                print(said)
    except KeyboardInterrupt:
        print()


def pairs(items):
    values = {}
    for item in items:
        key, separator, value = item.partition("=")
        if not separator or not key:
            sys.exit(f"'{item}'은 키=값 모양이 아닙니다. 예: rain=on")
        values[key] = value
    return values


def event_values(args):
    values = {"name": args.name}
    if args.min is not None:
        values["min"] = str(args.min)
    if args.km is not None:
        values["km"] = str(args.km)
    if getattr(args, "kind", None):
        values["kind"] = args.kind.upper()
    return values


def run_scenario(args):
    title, steps = SCENARIOS[args.name]
    print(f"{args.name}: {title}")
    send("clear", {}, args.serial)
    try:
        for index, (what, command, values) in enumerate(steps, 1):
            print(f"\n[{index}/{len(steps)}] {what}")
            print(f"  $ {command} " + " ".join(f"{key}={value}" for key, value in values.items()))
            print(indent(send(command, values, args.serial)))
            if args.next and command == "set":
                next_song(args.serial, args.wait)
            if index < len(steps):
                time.sleep(args.step)
    except KeyboardInterrupt:
        print("\n멈췄습니다.")
    finally:
        # 🔑 중간에 멈춰도 덧씌운 값을 남기지 않는다 — 남은 값이 다음 시험을 흐린다.
        if not args.keep:
            send("clear", {}, args.serial)
            print("\n덧씌운 신호를 모두 지웠습니다(--keep을 주면 남깁니다).")


def indent(text):
    return "\n".join("  " + line for line in text.splitlines())


def main():
    parser = argparse.ArgumentParser(description="DJ 트롯 차량 신호 시험 도구", epilog="자세한 쓰임은 파일 맨 위 설명을 봅니다.")
    parser.add_argument("-s", "--serial", default=os.environ.get("ANDROID_SERIAL"), help="adb 기기 번호(기본: ANDROID_SERIAL 또는 하나뿐인 기기)")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("keys", help="쓸 수 있는 키와 시나리오")
    commands.add_parser("show", help="지금 신호로 DJ가 할 말")
    commands.add_parser("clear", help="덧씌운 신호를 모두 지운다")
    setter = commands.add_parser("set", help="신호를 덧씌운다: 키=값 … (값이 -면 그 키를 지운다)")
    setter.add_argument("pairs", nargs="+", metavar="키=값")
    setter.add_argument("--next", action="store_true", help="바꾼 뒤 다음 곡으로 넘기고 DJ가 실제로 한 말을 찍는다")
    setter.add_argument("--wait", type=float, default=15, help="--next 뒤 멘트를 기다리는 시간, 초(기본 15)")
    commands.add_parser("watch", help="DJ가 실제로 하는 말을 나오는 대로 찍는다")
    key = commands.add_parser("media", help="재생 키를 보낸다(스티어링 휠 버튼처럼)")
    key.add_argument("key", choices=sorted(MEDIA_KEYS))
    for name, text in (("depart", "길 안내 시작"), ("waypoint", "경유지 도착"), ("arrive", "목적지 도착")):
        event = commands.add_parser(name, help=text)
        event.add_argument("name", help="곳 이름")
        event.add_argument("--min", type=int, help="걸리는(걸린) 시간, 분")
        event.add_argument("--km", type=float, help="거리, km")
        if name == "depart":
            event.add_argument("--kind", choices=["home", "office", "HOME", "OFFICE"], help="즐겨찾기의 집·회사")
    say = commands.add_parser("say", help="마이크 대신 말로 넣는 사연을 넣는다")
    say.add_argument("text")
    scenario = commands.add_parser("scenario", help="정해 둔 길을 차례로 흉내 낸다")
    scenario.add_argument("name", choices=sorted(SCENARIOS))
    scenario.add_argument("--step", type=float, default=15, help="단계 사이 쉬는 시간, 초(기본 15)")
    scenario.add_argument("--next", action="store_true", help="신호가 바뀔 때마다 다음 곡으로 넘기고 DJ가 실제로 한 말을 찍는다")
    scenario.add_argument("--wait", type=float, default=15, help="--next 뒤 멘트를 기다리는 시간, 초(기본 15)")
    scenario.add_argument("--keep", action="store_true", help="끝난 뒤 덧씌운 신호를 남긴다")
    args = parser.parse_args()

    if args.command == "keys":
        # 🔑 키 목록의 정본은 앱의 `Staging.KEYS`다 — 앱에 물어 그대로 보여 준다.
        print(send("keys", {}, args.serial))
        print("\n시나리오:")
        for name, (title, steps) in SCENARIOS.items():
            print(f"  {name:<13}{title} ({len(steps)}단계)")
    elif args.command in ("show", "clear"):
        print(send(args.command, {}, args.serial))
    elif args.command == "set":
        print(send("set", pairs(args.pairs), args.serial))
        if args.next:
            next_song(args.serial, args.wait)
    elif args.command == "watch":
        watch(args.serial)
    elif args.command == "media":
        media(args.key, args.serial)
    elif args.command in ("depart", "waypoint", "arrive"):
        print(send(args.command, event_values(args), args.serial))
    elif args.command == "say":
        print(send("say", {"text": args.text}, args.serial))
    elif args.command == "scenario":
        run_scenario(args)


if __name__ == "__main__":
    main()
