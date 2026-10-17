#!/usr/bin/env bash
# IceType 原生层构建脚本（Android arm64-v8a）
#
# 产出：
#   app/src/main/jniLibs/arm64-v8a/librime.so          —— librime 1.17.0 + librime-lua，静态链接全部依赖
#   app/src/main/jniLibs/arm64-v8a/libicetype_rime.so  —— IceType 自研 JNI 桥接层
#   app/src/main/jniLibs/arm64-v8a/libc++_shared.so    —— NDK 的 libc++（librime 需要）
#
# 为什么不用 Gradle 的 externalNativeBuild：
#   本机为 aarch64 宿主机，SDK 内未安装 cmake 组件，且这里需要先编译 Boost/OpenCC 等
#   大型第三方依赖。集中在本脚本里构建，可复现性更好，构建产物直接进入 jniLibs。
#
# 依赖的源码目录（由 scripts/prepare-sources.sh 准备）：
#   $WORK/librime             rime/librime（含 deps 子模块、plugins/librime-lua）
#   $WORK/librime-lua         hchunhui/librime-lua
#   $WORK/lua-5.4.7           lua 官方源码
#   $WORK/boost_1_86_0        Boost 源码（仅使用头文件 + Boost.Regex 源码）
set -euo pipefail

PROJ="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${ICETYPE_WORK:-/opt/ice}"
NDK="${ANDROID_NDK:-/opt/android-sdk/ndk/27.2.12479018}"
ANDROID_API="${ANDROID_API:-24}"
ABI=arm64-v8a
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
BIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CXX="$BIN/aarch64-linux-android${ANDROID_API}-clang++"
CC="$BIN/aarch64-linux-android${ANDROID_API}-clang"
AR="$BIN/llvm-ar"
STRIP="$BIN/llvm-strip"
JOBS="${JOBS:-2}"   # 并行过高时部分受限环境会触发 wait 系统调用限制，默认保守

PREFIX="$WORK/android-prefix"
LIBS="$WORK/android-libs"
mkdir -p "$PREFIX" "$LIBS"

echo "==> 环境检查"
for f in "$TOOLCHAIN" "$CXX" "$CC" "$AR"; do
  [ -e "$f" ] || { echo "缺少：$f"; exit 1; }
done
[ -d "$WORK/librime/src" ] || { echo "缺少 librime 源码：$WORK/librime"; exit 1; }

CMAKE_ARGS=(
  -G Ninja
  -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN"
  -DANDROID_ABI=$ABI
  -DANDROID_PLATFORM=android-$ANDROID_API
  -DANDROID_STL=c++_shared
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON
  -DCMAKE_INSTALL_PREFIX="$PREFIX"
  -DBUILD_SHARED_LIBS=OFF
)

# ———————————————————————————————————————————————————————————————
# 1) Boost：只编译 Boost.Regex（librime 依赖 boost::regex，其余为头文件）
# ———————————————————————————————————————————————————————————————
build_boost_regex() {
  local boost_src="$WORK/boost_1_86_0"
  [ -d "$boost_src/libs/regex/src" ] || { echo "跳过 Boost（未找到源码 $boost_src）"; return 0; }
  local out="$LIBS/boost-regex"
  if [ -f "$out/libboost_regex.a" ]; then
    echo "==> Boost.Regex 已存在，跳过"
    return 0
  fi
  echo "==> 编译 Boost.Regex（Android $ABI）"
  rm -rf "$out"; mkdir -p "$out"
  for f in "$boost_src"/libs/regex/src/*.cpp; do
    case "$(basename "$f")" in
      icu*) continue ;;   # Android 侧不启用 ICU（NDK 不提供 ICU 头），librime 只用 ASCII 正则
    esac
    "$CXX" -std=c++17 -O2 -fPIC -fvisibility=hidden -I"$boost_src" -c "$f" -o "$out/$(basename "$f").o"
  done
  "$AR" rcs "$out/libboost_regex.a" "$out"/*.o
  echo "==> Boost.Regex 完成"
}

# ———————————————————————————————————————————————————————————————
# 2) 第三方依赖：leveldb / marisa-trie / yaml-cpp / opencc
# ———————————————————————————————————————————————————————————————
build_dep() {
  local name="$1"; shift
  local src="$WORK/librime/deps/$name"
  local bdir="$src/.build-android"
  [ -d "$src" ] || { echo "缺少依赖源码：$src"; exit 1; }
  if [ -f "$PREFIX/lib/lib$name.a" ] || [ -f "$PREFIX/lib/lib${name//-/_}.a" ]; then
    echo "==> 依赖 $name 已安装，跳过"
    return 0
  fi
  echo "==> 构建依赖 $name"
  rm -rf "$bdir"; mkdir -p "$bdir"
  cmake -S "$src" -B "$bdir" "${CMAKE_ARGS[@]}" "$@"
  ninja -C "$bdir" -j"$JOBS"
  ninja -C "$bdir" install
}

# ———————————————————————————————————————————————————————————————
# 3) librime（含 librime-lua 插件，插件合并进同一个 .so）
# ———————————————————————————————————————————————————————————————
build_librime() {
  local src="$WORK/librime"
  local bdir="$src/.build-android"
  echo "==> 准备 librime-lua 插件（含 Lua 5.4 源码）"
  local plugins="$src/plugins/librime-lua"
  if [ ! -d "$plugins/src" ]; then
    mkdir -p "$plugins/thirdparty/lua5.4"
    cp "$WORK/librime-lua/CMakeLists.txt" "$WORK/librime-lua/LICENSE" "$plugins/"
    cp -r "$WORK/librime-lua/src" "$plugins/"
    cp "$WORK/lua-5.4.7/src"/*.c "$WORK/lua-5.4.7/src"/*.h "$plugins/thirdparty/lua5.4/"
    # 独立解释器/编译器的 main() 不能进库
    rm -f "$plugins/thirdparty/lua5.4/lua.c" "$plugins/thirdparty/lua5.4/luac.c"
  fi

  echo "==> 配置 librime"
  rm -rf "$bdir"
  cmake -S "$src" -B "$bdir" "${CMAKE_ARGS[@]}" \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_TEST=OFF \
    -DBUILD_SAMPLE=OFF \
    -DENABLE_LOGGING=OFF \
    -DENABLE_TIMESTAMP=OFF \
    -DCMAKE_PREFIX_PATH="$PREFIX" \
    -DBoost_INCLUDE_DIR="$WORK/boost_1_86_0" \
    -DBOOST_ROOT="$WORK/boost_1_86_0"
  echo "==> 编译 librime"
  ninja -C "$bdir" -j"$JOBS" rime
  echo "==> librime 完成：$bdir/librime.so.*"
}

# ———————————————————————————————————————————————————————————————
# 4) IceType JNI 桥接层
# ———————————————————————————————————————————————————————————————
build_jni() {
  local cpp="$PROJ/app/src/main/cpp/icetype_rime.cpp"
  local out="$LIBS/jni"
  local librime="$(ls "$WORK/librime/.build-android"/librime.so.* 2>/dev/null | head -1)"
  [ -n "$librime" ] || { echo "未找到 librime.so 构建产物"; exit 1; }
  echo "==> 编译 IceType JNI 层"
  rm -rf "$out"; mkdir -p "$out"
  "$CXX" -std=c++17 -O2 -fPIC -fvisibility=hidden \
    -I"$WORK/librime/src" -I"$WORK/boost_1_86_0" \
    -shared -o "$out/libicetype_rime.so" "$cpp" \
    -L"$(dirname "$librime")" -l:$(basename "$librime") \
    -llog
  echo "==> JNI 完成"
}

# ———————————————————————————————————————————————————————————————
# 5) 汇总到 jniLibs
# ———————————————————————————————————————————————————————————————
export_jni_libs() {
  local dest="$PROJ/app/src/main/jniLibs/$ABI"
  local librime="$(ls "$WORK/librime/.build-android"/librime.so.* | head -1)"
  mkdir -p "$dest"
  cp "$librime" "$dest/librime.so"
  cp "$LIBS/jni/libicetype_rime.so" "$dest/"
  local cxx_shared="$(find "$NDK" -name 'libc++_shared.so' -path "*$ABI*" | head -1)"
  [ -n "$cxx_shared" ] && cp "$cxx_shared" "$dest/"
  "$STRIP" --strip-unneeded "$dest/librime.so" "$dest/libicetype_rime.so" || true
  echo "==> jniLibs 就绪："
  ls -la "$dest"
}

build_boost_regex
build_dep leveldb     -DLEVELDB_BUILD_TESTS=OFF -DLEVELDB_BUILD_BENCHMARKS=OFF
build_dep marisa-trie -DBUILD_TESTING=OFF -DENABLE_TOOLS=OFF
build_dep yaml-cpp    -DYAML_CPP_BUILD_TESTS=OFF -DYAML_CPP_BUILD_TOOLS=OFF -DYAML_CPP_BUILD_CONTRIB=OFF -DYAML_CPP_BUILD_SHARED_LIBS=OFF
build_dep opencc      -DBUILD_TESTING=OFF -DBUILD_OPENCC_JIEBA_PLUGIN=OFF -DOPENCC_ENABLE_INSTALL=ON
build_librime
build_jni
export_jni_libs

echo "全部完成"