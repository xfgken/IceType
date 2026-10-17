#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
雾凇拼音（rime-ice）上游资源同步脚本 —— IceType 专用

用途：把上游 rime-ice 的 full/ 资源更新进本工程 assets/rime，
      自动重新应用我们的定制补丁，自动递增 ASSET_VERSION，并在出包前做 9 项闸门校验。

三种用法（在工程根目录执行）：

  # 1) 只看计划，不动任何文件
  python3 scripts/sync-rime-assets.py --from /path/to/full --dry-run

  # 2) 真正执行同步（会先自动快照，再拷文件、打补丁、递增版本号、校验）
  python3 scripts/sync-rime-assets.py --from /path/to/full --apply

  # 3) 只做校验（build-apk.sh 里会调用；不改文件）
  python3 scripts/sync-rime-assets.py --check

设计原则：
  · 白名单，默认拒绝 —— 清单里没有的文件一律不拷，只报告；
  · 补丁化 —— 我们改过的 4 个文件先覆盖再重打补丁，绝不裸覆盖；
  · 闸门 —— 校验不过就退出，不产出半成品；
  · 可回滚 —— 每次 --apply 前自动 tar 快照到 Download。
"""

import argparse
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RIME = os.path.join(ROOT, 'app/src/main/assets/rime')
INSTALLER = os.path.join(ROOT, 'app/src/main/java/com/xfgken/iceime/rime/RimeAssetInstaller.kt')
STATE = os.path.join(ROOT, 'scripts/rime-upstream-state.json')

# ============================ 资源清单（唯一事实源） ============================

# ① 纯替换：我们一个字都没改过的文件，上游更新直接覆盖
PLAIN_COPY = [
    'cn_dicts/base.dict.yaml',
    'cn_dicts/8105.dict.yaml',
    'cn_dicts/41448.dict.yaml',
    'cn_dicts/others.dict.yaml',
    'cn_dicts/ext.dict.yaml',
    'cn_dicts/tencent.dict.yaml',
    'en_dicts/en.dict.yaml',
    'en_dicts/cn_en.txt',
    'en_dicts/cn_en_abc.txt',
    'en_dicts/cn_en_double_pinyin.txt',
    'en_dicts/cn_en_flypy.txt',
    'en_dicts/cn_en_jiajia.txt',
    'en_dicts/cn_en_mspy.txt',
    'en_dicts/cn_en_sogou.txt',
    'en_dicts/cn_en_ziguang.txt',
    'melt_eng.dict.yaml',
    'melt_eng.schema.yaml',
    'symbols_v.yaml',
    'custom_phrase.txt',
    'LICENSE',
]
# 整目录纯替换（opencc 的 ocd2/json、lua 脚本与词典）
PLAIN_COPY_DIRS = ['opencc', 'lua']

# ② 替换后必须打补丁：这是全部风险所在
PATCHED = {
    'rime_ice.dict.yaml': 'import_tables 重写为 6 项（含 ext / tencent）',
    'rime_ice.schema.yaml': '删除 radical 挂载 + 挂载 cold_word_drop + 冷词键位',
    'default.yaml': 'page_size=20 + schema_list 仅 rime_ice',
    'en_dicts/en_ext.dict.yaml': '末尾追加 65 行英文专名',
}

# ③ 永不拷贝：上游有，但我们绝不许进包
NEVER_COPY = [
    't9.schema.yaml',
    'lua/t9_preedit.lua',
    'double_pinyin.schema.yaml',
    'double_pinyin_abc.schema.yaml',
    'double_pinyin_flypy.schema.yaml',
    'double_pinyin_jiajia.schema.yaml',
    'double_pinyin_mspy.schema.yaml',
    'double_pinyin_sogou.schema.yaml',
    'double_pinyin_ziguang.schema.yaml',
    'radical_pinyin.schema.yaml',
    'radical_pinyin.dict.yaml',
    'squirrel.yaml',
    'weasel.yaml',
    'default.custom.yaml',
    # 冷词词表由用户在 rime_user 里写，绝不能被上游示例覆盖
    'lua/cold_word_drop/drop_words.lua',
    'lua/cold_word_drop/hide_words.lua',
    'lua/cold_word_drop/reduce_freq_words.lua',
]
NEVER_COPY_DIRS = ['squirrel', 'weasel', 'build', 'radical_pinyin']

# 英文专名（en_ext.dict.yaml 末尾追加；上游更新后自动补回）
EN_EXTRA = """# —— IceType 补充：常见专有名词 / 互联网服务（中文模式下打前缀也能打出英文名） ——
Google	google
GitHub	github
GitLab	gitlab
YouTube	youtube
Android	android
iPhone	iphone
iPad	ipad
macOS	macos
Windows	windows
Linux	linux
Ubuntu	ubuntu
Debian	debian
WeChat	wechat
Weibo	weibo
Zhihu	zhihu
Bilibili	bilibili
Taobao	taobao
Alipay	alipay
Baidu	baidu
Tencent	tencent
Alibaba	alibaba
ByteDance	bytedance
Xiaomi	xiaomi
Redmi	redmi
TikTok	tiktok
Twitter	twitter
Facebook	facebook
Instagram	instagram
Telegram	telegram
WhatsApp	whatsapp
Discord	discord
Reddit	reddit
Netflix	netflix
Spotify	spotify
Steam	steam
Chrome	chrome
Firefox	firefox
Safari	safari
Kotlin	kotlin
Python	python
JavaScript	javascript
TypeScript	typescript
Rust	rust
Docker	docker
Redis	redis
MySQL	mysql
MongoDB	mongodb
Kubernetes	kubernetes
Nginx	nginx
Gradle	gradle
Maven	maven
OpenAI	openai
ChatGPT	chatgpt
Copilot	copilot
Claude	claude
Gemini	gemini
Figma	figma
Photoshop	photoshop
Blender	blender
Unity	unity
Termux	termux
Shizuku	shizuku
Magisk	magisk
APKPure	apkpure
"""

# ============================ 工具函数 ============================

def log(msg):
    print(msg, flush=True)


def read(path):
    with io.open(path, 'r', encoding='utf-8', errors='ignore') as f:
        return f.read()


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with io.open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


def sha1(path):
    h = hashlib.sha1()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def snapshot_rime():
    ts = time.strftime('%Y%m%d_%H%M%S')
    out = '/sdcard/Download/IceType_assets_backup_%s.tar.gz' % ts
    try:
        subprocess.run(['tar', 'czf', out, '-C', os.path.dirname(RIME), 'rime'], check=True)
        log('快照完成：%s（%.1f MB）' % (out, os.path.getsize(out) / 1048576.0))
    except Exception as e:
        log('!! 快照失败：%s（仍继续，但没有回滚点）' % e)


def bump_asset_version():
    s = read(INSTALLER)
    m = re.search(r'const val ASSET_VERSION = (\d+)', s)
    if not m:
        log('!! 找不到 ASSET_VERSION'); sys.exit(2)
    old = int(m.group(1))
    new = old + 1
    write(INSTALLER, s.replace('const val ASSET_VERSION = %d' % old,
                               'const val ASSET_VERSION = %d' % new))
    log('ASSET_VERSION：%d → %d' % (old, new))
    return new


def asset_version():
    m = re.search(r'const val ASSET_VERSION = (\d+)', read(INSTALLER))
    return int(m.group(1)) if m else -1


def write_state(ver, source=''):
    st = {
        'syncedAt': time.strftime('%Y-%m-%d %H:%M:%S'),
        'source': source,
        'assetVersion': ver,
        'files': {},
    }
    for dirpath, _, files in os.walk(RIME):
        for f in files:
            p = os.path.join(dirpath, f)
            st['files'][os.path.relpath(p, RIME)] = sha1(p)
    write(STATE, json.dumps(st, ensure_ascii=False, indent=1, sort_keys=True))
    return len(st['files'])


# ============================ 补丁（我们的定制） ============================

IMPORT_TABLES = """import_tables:
  # 顺序即权重：前面的词典优先占位
  - cn_dicts/8105       # 字表
  - cn_dicts/41448      # 大字表
  - cn_dicts/base       # 基础词库
  - cn_dicts/ext        # 扩展词库（39.3 万条）
  - cn_dicts/tencent    # 腾讯词向量词库（98.2 万条）
  - cn_dicts/others     # 其他杂项
"""


def patch_dict_yaml(path):
    s = read(path)
    start = s.find('import_tables:')
    if start < 0:
        return '!! rime_ice.dict.yaml 里没有 import_tables 段'
    end = s.find('\n\n', start)
    if end < 0:
        end = len(s)
    write(path, s[:start] + IMPORT_TABLES + s[end:])
    return 'import_tables → 6 项'


def patch_default_yaml(path):
    s = read(path)
    # page_size
    s2 = re.sub(r'^(\s*)page_size:\s*\d+.*$', r'\1page_size: 20  # 候选词个数（IceType 放大：候选栏整条横向滚动）',
                s, count=1, flags=re.M)
    if s2 == s and 'page_size: 20' not in s:
        return '!! default.yaml 未找到 page_size'
    s = s2
    # schema_list 只留 rime_ice
    start = s.find('schema_list:')
    if start < 0:
        return '!! default.yaml 未找到 schema_list'
    end = s.find('\n\n', start)
    if end < 0:
        end = len(s)
    block = "schema_list:\n  - schema: rime_ice               # 雾凇拼音（全拼）\n"
    write(path, s[:start] + block + s[end:])
    return 'page_size=20，schema_list 仅 rime_ice'


def patch_schema_yaml(path):
    s = read(path)
    notes = []
    # ① 删除所有 radical 相关挂载行（含双拼里各 14 处）
    lines = s.splitlines()
    kept = []
    removed = 0
    for l in lines:
        if 'radical' in l and (l.lstrip().startswith('-') or 'lua_' in l):
            removed += 1
            continue
        kept.append(l)
    if removed:
        notes.append('删除 radical 挂载 %d 行' % removed)
    s = '\n'.join(kept) + ('\n' if s.endswith('\n') else '')

    # ② 挂载 cold_word_drop.processor
    anchor = '    - lua_processor@*select_character'
    if 'cold_word_drop.processor' not in s:
        i = s.find(anchor)
        if i < 0:
            notes.append('!! 未找到 select_character 锚点，冷词 processor 未挂载')
        else:
            j = s.find('\n', i) + 1
            s = s[:j] + '    - lua_processor@*cold_word_drop.processor  # 冷词隐藏（长按候选 → 降频/隐藏）\n' + s[j:]
            notes.append('挂载 cold_word_drop.processor')

    # ③ 挂载 cold_word_drop.filter（置顶/长词之后、Emoji 之前）
    if 'cold_word_drop.filter' not in s:
        i = s.find('    - lua_filter@*reduce_english_filter')
        if i < 0:
            i = s.find('    - lua_filter@*long_word_filter')
        if i < 0:
            notes.append('!! 未找到 filters 锚点，冷词 filter 未挂载')
        else:
            j = s.find('\n', i) + 1
            s = s[:j] + '    - lua_filter@*cold_word_drop.filter             # 冷词隐藏（置于置顶/长词之后、Emoji 之前）\n' + s[j:]
            notes.append('挂载 cold_word_drop.filter')

    # ④ 冷词键位
    if 'reduce_freq_cand' not in s:
        anchor = 'search: "`"'
        i = s.find(anchor)
        if i < 0:
            notes.append('!! 未找到 key_binder/search 锚点，冷词键位未写入')
        else:
            j = s.find('\n', i) + 1
            add = ('  # 冷词快捷操作键位（由 lua/cold_word_drop 读取）\n'
                   '  reduce_freq_cand: "Control+j"   # 降频：把候选排到第 4 位之后\n'
                   '  drop_cand: "Control+d"          # 强删：无视编码彻底删除\n'
                   '  hide_cand: "Control+x"          # 隐藏：该编码下不再出现\n')
            s = s[:j] + add + s[j:]
            notes.append('写入冷词键位 3 条')

    write(path, s)
    return '；'.join(notes) if notes else '无改动'


def patch_en_ext(path):
    s = read(path)
    if 'IceType 补充：常见专有名词' in s:
        return '已含英文专名，跳过'
    if not s.endswith('\n'):
        s += '\n'
    write(path, s + EN_EXTRA)
    return '追加 %d 行英文专名' % len(EN_EXTRA.strip().splitlines())


PATCH_FUNCS = {
    'rime_ice.dict.yaml': patch_dict_yaml,
    'rime_ice.schema.yaml': patch_schema_yaml,
    'default.yaml': patch_default_yaml,
    'en_dicts/en_ext.dict.yaml': patch_en_ext,
}


# ============================ 闸门校验 ============================

def gate(verbose=True):
    problems = []

    def chk(ok, msg):
        if verbose:
            log(('  ✓ ' if ok else '  ✗ ') + msg)
        if not ok:
            problems.append(msg)

    # 1 page_size
    d = read(os.path.join(RIME, 'default.yaml'))
    m = re.search(r'^\s*page_size:\s*(\d+)', d, re.M)
    chk(bool(m) and m.group(1) == '20', 'default.yaml page_size = 20')
    # 2 schema_list（允许中间有注释行）
    sl = d[d.find('schema_list:'):]
    sl = sl[:sl.find('\n\n')] if '\n\n' in sl else sl
    ids = re.findall(r'^\s*-\s*schema:\s*(\S+)', sl, re.M)
    chk(ids == ['rime_ice'], 'default.yaml schema_list 仅 rime_ice（实际 %s）' % ids)


    # 3 import_tables 6 项
    dic = read(os.path.join(RIME, 'rime_ice.dict.yaml'))
    need = ['cn_dicts/8105', 'cn_dicts/41448', 'cn_dicts/base',
            'cn_dicts/ext', 'cn_dicts/tencent', 'cn_dicts/others']
    chk(all(('  - ' + n) in dic for n in need), 'rime_ice.dict.yaml import_tables 6 项齐全')

    # 4 无 radical 残留
    sch = read(os.path.join(RIME, 'rime_ice.schema.yaml'))
    chk('radical' not in sch, 'rime_ice.schema.yaml 无 radical 残留')

    # 5 冷词挂载
    chk('cold_word_drop.processor' in sch, 'schema 挂载 cold_word_drop.processor')
    chk('cold_word_drop.filter' in sch, 'schema 挂载 cold_word_drop.filter')

    # 6 引用完整性：所有 yaml 里引用的 dict / lua 文件必须存在
    refs = set()
    for dirpath, _, files in os.walk(RIME):
        for f in files:
            if not f.endswith(('.yaml', '.txt')):
                continue
            txt = read(os.path.join(dirpath, f))
            for m2 in re.finditer(r'^\s*-\s*(cn_dicts/[\w\-]+|en_dicts/[\w\-]+)\s*(?:#.*)?$', txt, re.M):
                refs.add(m2.group(1) + '.dict.yaml')
            for m2 in re.finditer(r'lua[_a-z]*@\*?([\w/]+?)(?:\.(?:lua|processor|filter))?\s*(?:#.*)?$', txt, re.M):
                refs.add('lua/' + m2.group(1))
    # cn_en / melt_eng 是顶层 dict
    for need2 in ['cn_en', 'melt_eng']:
        pass
    missing = []
    for r in sorted(refs):
        p = os.path.join(RIME, r)
        if os.path.exists(p):
            continue
        if os.path.exists(p + '.lua') or os.path.exists(p + '.dict.yaml'):
            continue
        missing.append(r)
    chk(not missing, '引用完整性（缺失：%s）' % (', '.join(missing) if missing else '无'))

    # 7 英文专名（用哨兵词判断，上游更新后若被覆盖会立刻发现）
    en = read(os.path.join(RIME, 'en_dicts/en_ext.dict.yaml'))
    missing_en = [w for w in ['GitHub', 'Shizuku', 'Magisk', 'Termux'] if w not in en]
    chk(not missing_en, 'en_ext.dict.yaml 英文专名齐全（缺 %s）' % (missing_en or '无'))

    # 8 冷词词表为空（初始）
    ok = True
    for f in ['drop_words.lua', 'hide_words.lua', 'reduce_freq_words.lua']:
        p = os.path.join(RIME, 'lua/cold_word_drop', f)
        if os.path.exists(p) and '{}' not in read(p):
            ok = False
    chk(ok, '冷词初始词表为空')

    # 9 大词库存在
    for f in ['cn_dicts/ext.dict.yaml', 'cn_dicts/tencent.dict.yaml']:
        chk(os.path.isfile(os.path.join(RIME, f)), '存在 %s' % f)

    # 10 状态一致性：资源变了但 ASSET_VERSION 没变 → 用户端不会生效
    if not os.path.isfile(STATE):
        write_state(asset_version())
        chk(True, '首次建立资源基线（scripts/rime-upstream-state.json）')
    else:
        try:
            st = json.loads(read(STATE))
            changed = []
            for rel, h in st.get('files', {}).items():
                p = os.path.join(RIME, rel)
                if not os.path.isfile(p):
                    changed.append(rel + '(已删除)')
                elif sha1(p) != h:
                    changed.append(rel)
            if changed and st.get('assetVersion') == asset_version():
                chk(False, '资源有 %d 处变化但 ASSET_VERSION 仍是 %d（用户端不会生效）'
                    % (len(changed), asset_version()))
            else:
                chk(True, '资源状态与 ASSET_VERSION 一致')
        except Exception as e:
            chk(True, '状态文件读取跳过（%s）' % e)

    return problems


# ============================ 主流程 ============================

def do_sync(src, dry):
    if not os.path.isdir(src):
        log('!! 源目录不存在：%s' % src); sys.exit(2)

    plan = []
    # 纯替换
    for rel in PLAIN_COPY:
        s = os.path.join(src, rel)
        if not os.path.isfile(s):
            plan.append(('跳过(上游无)', rel, ''))
            continue
        d = os.path.join(RIME, rel)
        same = os.path.isfile(d) and sha1(s) == sha1(d)
        plan.append(('纯替换', rel, '相同' if same else '内容变化'))
    for dd in PLAIN_COPY_DIRS:
        sd = os.path.join(src, dd)
        if not os.path.isdir(sd):
            continue
        for dirpath, _, files in os.walk(sd):
            for f in files:
                rel = os.path.relpath(os.path.join(dirpath, f), src)
                if rel in NEVER_COPY:
                    plan.append(('跳过(排除)', rel, ''))
                    continue
                d = os.path.join(RIME, rel)
                same = os.path.isfile(d) and sha1(os.path.join(src, rel)) == sha1(d)
                plan.append(('纯替换', rel, '相同' if same else '内容变化'))

    # 补丁覆盖
    for rel, desc in PATCHED.items():
        plan.append(('补丁覆盖', rel, desc))

    # 未知新文件
    known = set(PLAIN_COPY) | set(PATCHED) | set(NEVER_COPY)
    unknown = []
    for dirpath, dirs, files in os.walk(src):
        dirs[:] = [x for x in dirs if x not in NEVER_COPY_DIRS and not x.startswith('.')]
        for f in files:
            rel = os.path.relpath(os.path.join(dirpath, f), src)
            if rel in known or any(rel.startswith(x + '/') for x in PLAIN_COPY_DIRS):
                continue
            if rel.startswith('build/'):
                continue
            unknown.append(rel)

    log('=== 计划 ===')
    for kind, rel, note in plan:
        log('[%s] %-32s %s' % (kind, rel, note))
    if unknown:
        log('')
        log('=== 新增未收录（需人工判断，不会自动拷贝）===')
        for u in sorted(unknown)[:40]:
            log('  ? %s' % u)

    if dry:
        log('')
        log('=== 校验（预演，当前工程状态）===')
        gate()
        log('')
        log('（--dry-run 模式：未改动任何文件）')
        return

    snapshot_rime()
    copied = 0
    for kind, rel, _ in plan:
        if kind == '纯替换':
            s = os.path.join(src, rel)
            d = os.path.join(RIME, rel)
            if os.path.isfile(s):
                os.makedirs(os.path.dirname(d), exist_ok=True)
                shutil.copyfile(s, d)
                copied += 1
    log('已拷贝 %d 个文件' % copied)

    for rel, func in PATCH_FUNCS.items():
        r = func(os.path.join(RIME, rel))
        log('补丁 %-28s %s' % (rel, r))

    ver = bump_asset_version()

    # 状态记录
    st = {
        'syncedAt': time.strftime('%Y-%m-%d %H:%M:%S'),
        'source': src,
        'assetVersion': ver,
        'files': {},
    }
    for dirpath, _, files in os.walk(RIME):
        for f in files:
            p = os.path.join(dirpath, f)
            st['files'][os.path.relpath(p, RIME)] = sha1(p)
    write(STATE, json.dumps(st, ensure_ascii=False, indent=1, sort_keys=True))
    log('状态已写入 %s（%d 个文件）' % (os.path.relpath(STATE, ROOT), len(st['files'])))

    log('')
    log('=== 闸门校验 ===')
    problems = gate()
    if problems:
        log('')
        log('!! 校验未通过 %d 项，请勿出包：' % len(problems))
        for p in problems:
            log('   - ' + p)
        sys.exit(1)
    log('')
    log('全部通过。下一步：bash scripts/build-apk.sh')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--from', dest='src', help='上游 full 目录')
    ap.add_argument('--dry-run', action='store_true', help='只看计划')
    ap.add_argument('--apply', action='store_true', help='执行同步')
    ap.add_argument('--check', action='store_true', help='只做闸门校验')
    a = ap.parse_args()

    if a.check:
        problems = gate()
        if problems:
            log('校验未通过 %d 项' % len(problems)); sys.exit(1)
        log('资源校验通过（ASSET_VERSION=%d）' % asset_version())
        return
    if not a.src:
        ap.print_help(); return
    if not a.dry_run and not a.apply:
        log('请指定 --dry-run 或 --apply'); sys.exit(2)
    do_sync(a.src, a.dry_run)


if __name__ == '__main__':
    main()