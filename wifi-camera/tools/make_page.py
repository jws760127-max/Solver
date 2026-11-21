#!/usr/bin/env python3
"""web/index.html을 gzip으로 압축해 펌웨어에 넣을 page.h로 만들어요."""
import gzip, pathlib
root = pathlib.Path(__file__).resolve().parent.parent
html = (root / 'web' / 'index.html').read_bytes()
gz = gzip.compress(html, compresslevel=9, mtime=0)
rows = [', '.join(f'0x{b:02x}' for b in gz[i:i + 20]) for i in range(0, len(gz), 20)]
out = ('// 자동 생성 파일 — web/index.html을 고친 뒤 tools/make_page.py로 다시 만들어요\n#pragma once\n#include <Arduino.h>\n'
       f'const uint8_t index_html_gz[] PROGMEM = {{\n  ' + ',\n  '.join(rows) + '\n};\n'
       f'const size_t index_html_gz_len = {len(gz)};\n')
(root / 'firmware' / 'SolveCam' / 'page.h').write_text(out)
print(f'page.h: {len(html)} → {len(gz)} bytes')
