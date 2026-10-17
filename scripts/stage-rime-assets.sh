#!/usr/bin/env bash
# 整理 APK 需要的全部数据资源：
#   1) Rime（雾凇拼音）资源 → app/src/main/assets/rime/
#   2) 联想词表            → app/src/main/assets/predict/lexicon.txt
#
# 用法：scripts/stage-rime-assets.sh [Rime 源目录] [opencc 数据目录]
#   默认 Rime 源目录：/storage/emulated/0/AppProjects/IceTypedevdata/full_compiled
#     —— 该目录同时包含源词典与 build/ 预编译产物，是「打开就能用」所需的完整集合；
#        若要改成体积优先（APK 小 74MB），可改用 full/ 并加上 ICETYPE_SKIP_PREBUILT=1。
#   默认 opencc 数据目录：/opt/ice/host/share/opencc（OpenCC 构建产物，数据与平台无关）
#
# 环境变量：
#   ICETYPE_SKIP_PREBUILT=1  不打包 build/ 里的预编译产物（APK 约小 74MB，
#                            代价是首次启动需要现场编译一次词典）
#
# 打包策略（全部基于实测结论，见 docs/TEST_REPORT.md）：
#
#  1) 必须包含 cn_dicts/ 等**源词典**。
#     实测：去掉源词典后部署依然返回成功（deploy: success），但中文候选全部失效
#     —— 输入 nihao 得到 0 个候选，且英文候选会顶到第一位。
#
#  2) 必须把 OpenCC 的数据放进 assets/rime/opencc/。
#     依据 librime 源码 src/rime/gear/simplifier.cc：相对路径的 opencc_config
#     （如 t2s.json）会依次在 ${user_data_dir}/opencc/ 与 ${shared_data_dir}/opencc/
#     下查找。Android 设备上不存在 OpenCC 的安装目录，因此必须自带。
#     实测：把 OpenCC 数据放入 shared 目录并移走系统 OpenCC 安装目录后，
#     简繁转换仍然正常（漢字 / 汉字 切换成功）。
#
#  3) 默认打包 build/ 内的预编译产物（rime_ice.table.bin 等），实现「打开就能用」。
#     实测（宿主机）：带预编译产物时整轮探测 11 秒；仅有源文件时 35 秒。
#     设备端实测：仅源文件时首次部署 28.14 秒。
#
#  4) 不打包 sync/、*.userdb、installation.yaml、user.yaml 等用户数据。
set -euo pipefail

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${1:-/storage/emulated/0/AppProjects/IceTypedevdata/full_compiled}"
OPENCC_DATA="${2:-/opt/ice/host/share/opencc}"
DEST="$PROJ/app/src/main/assets/rime"
PREDICT="$PROJ/app/src/main/assets/predict"

[ -d "$SRC" ] || { echo "Rime 源目录不存在：$SRC"; exit 1; }

echo "==> 清理旧资源"
rm -rf "$DEST" "$PREDICT"
mkdir -p "$DEST" "$PREDICT"

EXCLUDES=(
  --exclude='./.git'
  --exclude='./sync'
  --exclude='./*.userdb'
  --exclude='__pycache__'
  --exclude='./installation.yaml'
  --exclude='./user.yaml'
)
if [ "${ICETYPE_SKIP_PREBUILT:-0}" = "1" ]; then
  echo "==> 按 ICETYPE_SKIP_PREBUILT=1 跳过 build/ 预编译产物"
  EXCLUDES+=(--exclude='./build')
fi

echo "==> 复制 Rime 资源"
(cd "$SRC" && tar cf - "${EXCLUDES[@]}" .) | (cd "$DEST" && tar xf - --no-same-permissions)

if [ -d "$OPENCC_DATA" ]; then
  echo "==> 合并 OpenCC 数据到 assets/rime/opencc/"
  mkdir -p "$DEST/opencc"
  # 先放 OpenCC 官方数据，再让 Rime Ice 的补充文件（emoji.json 等）覆盖同名文件
  cp -r "$OPENCC_DATA"/. "$DEST/opencc/"
  if [ -d "$SRC/opencc" ]; then
    cp -r "$SRC/opencc"/. "$DEST/opencc/"
  fi
  echo "    OpenCC 文件数：$(ls "$DEST/opencc" | wc -l)"
else
  echo "!! 未找到 OpenCC 数据目录 $OPENCC_DATA"
  echo "!! 缺它会导致简繁转换不可用，请先构建 OpenCC 或手动指定目录"
fi

echo "==> 生成联想词表"
if [ -f "$SRC/cn_dicts/base.dict.yaml" ]; then
  python3 "$PROJ/scripts/gen-predict-lexicon.py" "$SRC/cn_dicts" "$PREDICT/lexicon.txt"
else
  echo "!! 未找到 $SRC/cn_dicts，跳过联想词表生成（选词后的联想将不可用）"
fi

echo "==> 结果"
du -sh "$DEST" "$PREDICT"
echo "assets 文件总数：$(find "$PROJ/app/src/main/assets" -type f | wc -l)"
echo "顶层内容："
ls "$DEST"