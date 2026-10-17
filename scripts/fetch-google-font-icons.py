#!/usr/bin/env python3
"""从 fonts.google.com/icons 的官方 SVG 生成 Android VectorDrawable。

图标来源（该页面实际加载的资源）：
  https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined/<name>/default/24px.svg

脚本做两件事：
  1. 把官方 SVG 原样缓存到 docs/google-icons-svg/（离线可复现）；
  2. 读缓存的 SVG，转成 app/src/main/res/drawable/<资源名>.xml。

转换要点：SVG 的 y 轴范围是 -960..0，VectorDrawable 没有 viewBox 偏移，
因此把路径包在 <group android:translateY="960"> 中平移回 0..960。

用法：scripts/fetch-google-font-icons.py <SVG 缓存目录> <drawable 输出目录>
"""
import re
import sys
import urllib.request
from pathlib import Path

BASE = "https://fonts.gstatic.com/s/i/short-term/release/materialsymbolsoutlined"

ICONS = {
    "ic_brand": "ac_unit",
    "ic_tune": "settings",
    "ic_settings": "settings",
    "ic_emoji": "mood",
    "ic_language": "language",
    "ic_clipboard": "content_paste",
    "ic_keyboard_hide": "keyboard_hide",
    "ic_keyboard": "keyboard",
    "ic_backspace": "backspace",
    "ic_shift": "shift",
    "ic_enter": "keyboard_return",
    "ic_status_disabled": "radio_button_unchecked",
    "ic_status_enabled": "check_circle",
    "ic_status_active": "task_alt",
    "ic_theme": "contrast",
    "ic_sound": "volume_up",
    "ic_vibrate": "vibration",
    "ic_phrase": "text_snippet",
    "ic_book": "menu_book",
    "ic_shield": "privacy_tip",
    "ic_info": "info",
    "ic_list": "receipt_long",
    "ic_chevron_right": "chevron_right",
    "ic_chevron_left": "chevron_left",
    "ic_chevron_up": "expand_less",
    "ic_chevron_down": "expand_more",
    "ic_more_vert": "more_vert",
    "ic_close": "close",
    "ic_check": "check",
    "ic_cancel": "cancel",
    "ic_warning": "warning",
    "ic_add": "add",
    "ic_arrow_back": "arrow_back",
    "ic_copy": "content_copy",
    "ic_delete": "delete",
    "ic_edit": "edit",
    "ic_pin": "push_pin",
    "ic_refresh": "refresh",
    "ic_export": "file_upload",
    "ic_numbers": "dialpad",
    "ic_symbols": "emoji_symbols",
    "ic_light_mode": "light_mode",
    "ic_dark_mode": "dark_mode",
}

PATH_RE = re.compile(r'<path[^>]*\sd="([^"]+)"')


def fetch(cache_dir, name):
    cached = cache_dir / f"{name}.svg"
    if cached.is_file() and cached.stat().st_size > 0:
        return cached.read_text(encoding="utf-8")
    url = f"{BASE}/{name}/default/24px.svg"
    try:
        with urllib.request.urlopen(url, timeout=30) as resp:
            if resp.status != 200:
                print(f"下载失败 {name}: HTTP {resp.status}")
                return None
            svg = resp.read().decode("utf-8")
    except Exception as exc:  # noqa: BLE001
        print(f"下载失败 {name}: {exc}")
        return None
    cache_dir.mkdir(parents=True, exist_ok=True)
    cached.write_text(svg, encoding="utf-8")
    return svg


def to_vector(svg, name):
    paths = PATH_RE.findall(svg)
    if not paths:
        return None
    body = "".join(
        f'    <path\n        android:pathData="{d}"\n'
        '        android:fillColor="#FF000000" />\n'
        for d in paths
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!--\n"
        f'  fonts.google.com/icons 官方图标 "{name}"（Material Symbols Outlined, 24px）。\n'
        f"  原始 SVG 缓存在 docs/google-icons-svg/{name}.svg\n"
        "  由 scripts/fetch-google-font-icons.py 自动转换，请勿手工修改。\n"
        "  Material Symbols 采用 Apache License 2.0。\n"
        "-->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        '    android:viewportWidth="960"\n'
        '    android:viewportHeight="960">\n'
        '    <group android:translateY="960">\n'
        f"{body}"
        "    </group>\n"
        "</vector>\n"
    )


def launcher_foreground(svg):
    paths = PATH_RE.findall(svg)
    if not paths:
        return None
    body = "".join(
        f'        <path\n            android:pathData="{d}"\n'
        '            android:fillColor="#FFEAF4FF" />\n'
        for d in paths
    )
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<!--\n"
        "  自适应图标前景：fonts.google.com/icons 官方图标 ac_unit（冰晶）。\n"
        "  外层 group 把它放进 108dp 画布的中央安全区内。\n"
        "-->\n"
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="108dp"\n'
        '    android:height="108dp"\n'
        '    android:viewportWidth="108"\n'
        '    android:viewportHeight="108">\n'
        '    <group\n        android:translateX="23"\n        android:translateY="23"\n'
        '        android:scaleX="0.065"\n        android:scaleY="0.065">\n'
        '        <group android:translateY="960">\n'
        f"{body}"
        "        </group>\n"
        "    </group>\n"
        "</vector>\n"
    )


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    cache_dir = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)

    ok, failed = [], []
    for res_name, name in ICONS.items():
        svg = fetch(cache_dir, name)
        if not svg:
            failed.append(res_name)
            continue
        xml = to_vector(svg, name)
        if not xml:
            failed.append(res_name)
            continue
        (out_dir / f"{res_name}.xml").write_text(xml, encoding="utf-8")
        ok.append(res_name)

    land = fetch(cache_dir, "ac_unit")
    if land:
        fg = launcher_foreground(land)
        if fg:
            (out_dir / "ic_launcher_foreground.xml").write_text(fg, encoding="utf-8")
            ok.append("ic_launcher_foreground")

    print(f"已生成 {len(ok)} 个图标（来自 fonts.google.com/icons）")
    if failed:
        print("失败：" + " ".join(failed))
    return 0


if __name__ == "__main__":
    sys.exit(main())