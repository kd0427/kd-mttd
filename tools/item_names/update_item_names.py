#!/usr/bin/env python3
"""
item_names_ko.json(앱 번들 아이템 사전) 시즌 갱신.

    python3 tools/item_names/update_item_names.py <작업폴더> [--etor-ok id,id,...]

작업폴더에 원본 데이터와 위키 페이지 캐시, 확인용 아이콘 모음(sheet*.png)이 쌓인다.
저장소 밖(임시 폴더)을 쓸 것. 사전 파일은 제자리에서 고친다 — 커밋 전에 git diff 로 본다.

출처
- 이름·종류: TTD 시세(ttdiablo.com/ttd_price.json). 앱의 시세 출처와 같아서 표기를 맞춘다.
- 아이콘: tlidb 위키 아이템 페이지. 페이지에 `id: N` 이 적힌 칸의 그림만 쓴다 — 같은 이름에
  등급별 아이콘이 여러 개 있는 페이지(신격·추천서)도 ID 로 정확히 갈린다.
- 위키에 없는 것: ETor 사이트 데이터(game_static_data*.js). 중국어 이름이라 사람이 같은
  아이템인지 확인한 ID 만 --etor-ok 로 넘긴다 (실행하면 후보를 출력한다).

규칙
- TTD 에 없는 기존 항목은 지우지 않는다 (시세 목록에 없다고 게임에서 사라진 게 아니다).
- 게임이 지난 시즌 ID 를 새 아이템에 재사용하면 이름이 바뀐다. 그때 옛 아이콘은 버린다.
- 아이콘 URL 은 실제로 받아지는 것만 넣는다 (cdn.tlidb.com 은 Referer 가 있어야 열린다).

다 돌고 나면 sheet*.png 를 열어 이름과 그림이 맞는지 눈으로 확인한다.
2026-10-22 첫 사용(S16): 43개 추가, 4개 이름 변경, 57개 아이콘 보완.
"""
import argparse
import html as H
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
ASSET = os.path.join(REPO, 'app/src/main/assets/item_names_ko.json')
UA = {'User-Agent': 'Mozilla/5.0', 'Referer': 'https://tlidb.com/'}
ICON = re.compile(r'(?:https:)?//cdn\.tlidb\.com/[^"\s]+?\.(?:webp|png)')
ETOR_ITEM = re.compile(
    r'\{"name":"((?:[^"\\]|\\.)*)","type":"((?:[^"\\]|\\.)*)","img":"((?:[^"\\]|\\.)*)","id":"(\d+)"')
TIER = re.compile(r'\s+(노멀|매직|레어|레전드|절대자)Lv\d+$')


def get(url, path=None):
    if path and os.path.exists(path):
        return open(path, encoding='utf-8').read()
    body = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read().decode('utf-8')
    if path:
        open(path, 'w', encoding='utf-8').write(body)
    return body


def fetch_sources(work):
    ttd = json.loads(get('https://ttdiablo.com/ttd_price.json', f'{work}/ttd.json'))
    ac = json.loads(get('https://tlidb.com/i18n/autocomplete_ko.json', f'{work}/autocomplete_ko.json'))
    index = get('https://etor-zero.981001.xyz/', f'{work}/etor_index.html')
    js = re.search(r'/assets/game_static_data-[^"]+\.js', index).group(0)
    etor_js = get('https://etor-zero.981001.xyz' + js, f'{work}/etor_game_static_data.js')
    return ttd, ac, etor_js


def wiki_page(work, slug):
    os.makedirs(f'{work}/pages', exist_ok=True)
    path = f'{work}/pages/{slug}.html'
    if os.path.exists(path):
        return open(path, encoding='utf-8').read()
    try:
        body = get('https://tlidb.com/ko/' + urllib.parse.quote(slug))
    except Exception:
        body = ''
    open(path, 'w', encoding='utf-8').write(body)
    time.sleep(0.5)
    return body


def section_icon(page, item_id):
    """`id: N` 이 적힌 칸(앞 칸의 id 뒤부터 이 id 까지) 안의 아이콘. 정확히 하나일 때만."""
    ids = list(re.finditer(r'id:\s*(\d+)', page))
    mine = [m for m in ids if m.group(1) == item_id]
    if len(mine) != 1:
        return None
    j = ids.index(mine[0])
    seg = page[(ids[j - 1].end() if j else 0):mine[0].start()]
    icons = {('https:' + m.group(0)) if m.group(0).startswith('//') else m.group(0)
             for m in ICON.finditer(seg) if '/image/' not in m.group(0)}
    return icons.pop() if len(icons) == 1 else None


def etor_url(u):
    # ETor 는 같은 텍스처를 cdn 이 아닌 경로로 적어 둔 게 있다 — 사전이 쓰는 cdn UIV2 경로로 맞춘다.
    if u.startswith('https://tlidb.com/'):
        u = 'https://cdn.tlidb.com/UIV2/' + u[len('https://tlidb.com/'):]
    return u.replace('cdn.tlidb.com/UI/Textures/S14Gameplay/', 'cdn.tlidb.com/UIV2/S14Gameplay/')


def reachable(u):
    # 열 번 안에 한 번이라도 열리면 넣는다. cdn.tlidb.com 은 멀쩡한 그림에도 403 을 가끔 섞어 주고,
    # 잘 안 열리는 그림도 있다 (S15 신의 잔해 06·11 번: 20 번 중 3·8 번, 2026-10-22). 그래도
    # 주소가 있으면 앱이 띄울 때마다 다시 시도하고 한 번 받으면 캐시하므로 결국 뜬다. 주소를 빼면
    # 영영 안 뜬다 — 한 번도 안 열리는 것만 뺀다.
    for _ in range(10):
        try:
            with urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=20) as r:
                if r.status == 200 and len(r.read()) > 100:
                    return True
        except Exception:
            pass
        time.sleep(0.3)
    return False


def contact_sheets(work, entries):
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        print('Pillow 가 없어 아이콘 모음은 건너뜀 — pip install pillow')
        return
    os.makedirs(f'{work}/icons', exist_ok=True)
    font = ImageFont.truetype('/System/Library/Fonts/AppleSDGothicNeo.ttc', 13)
    w, h, cols = 230, 96, 5
    for n, chunk in enumerate(entries[i:i + 40] for i in range(0, len(entries), 40)):
        sheet = Image.new('RGB', (w * cols, h * ((len(chunk) + cols - 1) // cols)), (40, 44, 52))
        d = ImageDraw.Draw(sheet)
        for i, (k, v) in enumerate(chunk):
            f = f'{work}/icons/{k}.img'
            if not os.path.exists(f):
                try:
                    data = urllib.request.urlopen(urllib.request.Request(v['img'], headers=UA), timeout=30).read()
                except Exception as e:
                    print(f'아이콘 모음용 다운로드 실패: {k} {e}')
                    continue
                open(f, 'wb').write(data)
            im = Image.open(f).convert('RGBA')
            im.thumbnail((64, 64))
            x, y = (i % cols) * w, (i // cols) * h
            sheet.paste(im, (x + 4, y + 4), im)
            d.text((x + 72, y + 6), k, fill=(150, 200, 255), font=font)
            for j in range(0, len(v['name']), 11):
                d.text((x + 72, y + 24 + (j // 11) * 16), v['name'][j:j + 11], fill=(230, 230, 230), font=font)
        sheet.save(f'{work}/sheet{n}.png')
        print(f'확인용 아이콘 모음: {work}/sheet{n}.png')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('work')
    ap.add_argument('--etor-ok', default='', help='ETor 아이콘을 써도 된다고 확인한 id (쉼표 구분)')
    args = ap.parse_args()
    work = os.path.abspath(args.work)
    os.makedirs(work, exist_ok=True)
    etor_ok = {x for x in args.etor_ok.split(',') if x}

    ttd, ac, etor_js = fetch_sources(work)
    old = json.load(open(ASSET, encoding='utf-8'))
    slug_of = {}
    for x in ac:
        slug_of.setdefault(x['label'], x['value'])
    etor = {}
    for m in ETOR_ITEM.finditer(etor_js):
        etor.setdefault(m.group(4), []).append((m.group(1), m.group(3)))

    # 손볼 대상: TTD 에 새로 생긴 것, 이름이 바뀐 것(ID 재사용), 아이콘이 비어 있는 것.
    targets = {}
    for k, v in ttd.items():
        if k not in old:
            targets[k] = 'new'
        elif (v['name'], v['type']) != (old[k].get('name'), old[k].get('type')):
            targets[k] = 'renamed'
    for k, v in old.items():
        if not v.get('img') and k not in targets:
            targets[k] = 'noimg'

    out = dict(old)
    etor_candidates = []
    for k, why in sorted(targets.items(), key=lambda kv: int(kv[0])):
        e = dict(out.get(k, {}))
        if why in ('new', 'renamed'):
            e['name'], e['type'] = ttd[k]['name'], ttd[k]['type']
            if why == 'renamed':
                e.pop('img', None)
        name = e['name']
        slug = slug_of.get(name) or slug_of.get(TIER.sub('', name))
        img = section_icon(wiki_page(work, slug), k) if slug else None
        if not img and len(etor.get(k, [])) == 1:
            if k in etor_ok:
                img = etor_url(etor[k][0][1])
            else:
                etor_candidates.append((k, name, etor[k][0][0]))
        if img and reachable(img):
            e['img'] = img
        elif img:
            print(f'열리지 않는 아이콘이라 뺌: {k} {name} {img}')
        out[k] = {x: e[x] for x in ('name', 'type', 'img') if x in e}

    out = {k: out[k] for k in sorted(out, key=int)}
    open(ASSET, 'w', encoding='utf-8').write(json.dumps(out, ensure_ascii=False, separators=(',', ':')))

    changed = [(k, v) for k, v in out.items() if old.get(k) != v]
    print(f'항목 {len(old)} → {len(out)}, 바뀐 것 {len(changed)}')
    print('아이콘 없음:', [(k, v['name']) for k, v in out.items() if not v.get('img')])
    if etor_candidates:
        print('\nETor 에만 아이콘이 있는 것 — 중국어 이름이 같은 아이템이면 --etor-ok 로 다시 실행:')
        for k, name, zh in etor_candidates:
            print(f'  {k}  {name}  ←  {zh}')
    contact_sheets(work, [(k, v) for k, v in changed if v.get('img')])


if __name__ == '__main__':
    sys.exit(main())
