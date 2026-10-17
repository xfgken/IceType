#!/usr/bin/env bash
# 生成「精简版 Rime 资源集」：只保留全拼（rime_ice）+ 英文候选 + 简繁转换所需的内容。
#
# 用法：scripts/make-minimal-rime-set.sh <原始资源目录> <输出目录> [宿主机 librime 前缀]
#
# 被裁掉的内容（以及原因）：
#   · double_pinyin*.schema.yaml（8 个）、t9.schema.yaml —— 键盘尚未提供双拼与九宫格布局，
#     保留只会在方案列表里出现用不到的条目；
#   · radical_pinyin.*（源 2.1MB + 产物 5.6MB）—— 「部件拆字」反查功能，配合相关
#     translator / filter / recognizer 一并移除；
#   · cn_dicts/ext.dict.yaml（12MB）、cn_dicts/tencent.dict.yaml（17MB）—— 扩展词库与
#     腾讯词向量；保留 8105 字表 + 41448 大字表 + base 基础词库 + others 杂项；
#   · squirrel.yaml / weasel.yaml / symbols_caps_v.yaml —— 鼠须管 / 小狼毫前端专用配置；
#   · build/ 下的预编译产物 —— 不复制，改为用**同一版本 librime** 重新部署生成，
#     确保「源词典」与「编译产物」严格匹配。
#
# 关键点：源词典与 build 产物必须来自同一份源。裁剪了词典却不重编译，会导致
# 设备端校验失败并现场重建，甚至出现「部署成功但候选为空」的情况。
set -euo pipefail

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:-/storage/emulated/0/AppProjects/IceTypedevdata/full_compiled}"
DST="${2:-/opt/ice/min/shared}"
HOST_PREFIX="${3:-/opt/ice/host}"

[ -d "$SRC" ] || { echo "源目录不存在：$SRC"; exit 1; }

echo "==> 整理精简资源到 $DST"
rm -rf "$DST"
mkdir -p "$DST/cn_dicts"

cp "$SRC/LICENSE" "$SRC/default.yaml" "$SRC/rime_ice.schema.yaml" "$SRC/rime_ice.dict.yaml" \
   "$SRC/melt_eng.dict.yaml" "$SRC/custom_phrase.txt" "$SRC/symbols_v.yaml" "$DST/"
cp -r "$SRC/lua" "$SRC/en_dicts" "$SRC/opencc" "$DST/"
rm -f "$DST/lua/t9_preedit.lua"

for f in base.dict.yaml 8105.dict.yaml 41448.dict.yaml others.dict.yaml; do
  cp "$SRC/cn_dicts/$f" "$DST/cn_dicts/"
done

python3 - "$DST" <<'PY'
import re
import sys
from pathlib import Path

dst = Path(sys.argv[1])

p = dst / "rime_ice.dict.yaml"
text = p.read_text(encoding="utf-8")
text = re.sub(r"^\s*-\s*cn_dicts/ext\b.*$", "", text, flags=re.M)
text = re.sub(r"^\s*-\s*cn_dicts/tencent\b.*$", "", text, flags=re.M)
text = re.sub(r"^\s*#\s*-\s*cn_dicts/41448.*$", "  - cn_dicts/41448  # 大字表（已启用）",
              text, flags=re.M)
p.write_text(text, encoding="utf-8")

p = dst / "default.yaml"
lines = [l for l in p.read_text(encoding="utf-8").splitlines()
         if not re.match(r"^\s*-\s*schema:\s*(t9|double_pinyin)", l)]
p.write_text("\n".join(lines) + "\n", encoding="utf-8")

p = dst / "rime_ice.schema.yaml"
out_lines = []
skip_block = False
for line in p.read_text(encoding="utf-8").splitlines():
    if skip_block:
        if line and not line[0].isspace() and not line.lstrip().startswith("#"):
            skip_block = False
        else:
            continue
    if re.match(r"^(radical_lookup|radical_reverse_lookup):\s*$", line):
        skip_block = True
        continue
    if "radical" in line.strip():
        continue
    out_lines.append(line)
p.write_text("\n".join(out_lines) + "\n", encoding="utf-8")
print("已精简 rime_ice.dict.yaml / default.yaml / rime_ice.schema.yaml")
PY

echo "==> 精简后（未含产物）："
du -sh "$DST"
find "$DST" -type f | wc -l

echo "==> 用宿主机 librime 重新部署，生成匹配的预编译产物"
USER_DIR="${DST}_user"
rm -rf "$USER_DIR"
mkdir -p "$USER_DIR"

# 复用验证程序同一套 API 调用：直接写一个小程序做部署最稳（rime_deployer 的参数在不同版本有差异）
PROBE="${PROBE_BIN:-/opt/ice/verify/rime_probe}"
if [ -x "$PROBE" ]; then
  "$PROBE" "$DST" "$USER_DIR" > /opt/ice/min/deploy.log 2>&1 || true
  if [ -d "$USER_DIR/build" ]; then
    cp -r "$USER_DIR/build" "$DST/build"
    echo "    产物已生成：$(du -sh "$DST/build" | cut -f1)"
  else
    echo "!! 未生成 build 产物，请检查 /opt/ice/min/deploy.log"
  fi
else
  echo "!! 未找到部署程序 $PROBE（见 docs/TEST_PLAN.md）"
fi

echo "==> 完成：$DST 可直接作为 stage-rime-assets.sh 的源目录"
du -sh "$DST"