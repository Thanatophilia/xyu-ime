#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK="$ROOT/.native"
mkdir -p "$WORK"
if [ ! -d "$WORK/librime/.git" ]; then
  git clone https://github.com/rime/librime.git "$WORK/librime"
fi
git -C "$WORK/librime" checkout 33e78140250125871856cdc5b42ddc6a5fcd3cd4
git -C "$WORK/librime" submodule update --init deps/leveldb deps/marisa-trie deps/opencc deps/yaml-cpp
# Boost >=1.77 implements regex header-only. Android does not link host Boost libraries.
if [ ! -d "$WORK/boost_1_85_0" ]; then
  curl -fL https://archives.boost.io/release/1.85.0/source/boost_1_85_0.tar.bz2 -o "$WORK/boost.tar.bz2"
  echo '7009fe1faa1697476bdc7027703a2badb84e849b7b0baad5086b087b971f8617  '"$WORK/boost.tar.bz2" | sha256sum -c
  tar -xjf "$WORK/boost.tar.bz2" -C "$WORK"
fi
NDK="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/28.2.13676358}"
for ABI in arm64-v8a x86_64; do
  PREFIX="$WORK/install/$ABI"
  COMMON=(-G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" -DANDROID_ABI="$ABI" -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_shared -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DCMAKE_INSTALL_PREFIX="$PREFIX" -DCMAKE_INSTALL_LIBDIR=lib -DBUILD_SHARED_LIBS=OFF -DCMAKE_POLICY_VERSION_MINIMUM=3.5)
  build() {
    local name="$1"; shift
    cmake -S "$WORK/librime/deps/$name" -B "$WORK/build/$ABI/$name" "${COMMON[@]}" "$@"
    cmake --build "$WORK/build/$ABI/$name" --target install --parallel 3
  }
  build leveldb -DLEVELDB_BUILD_TESTS=OFF -DLEVELDB_BUILD_BENCHMARKS=OFF
  build yaml-cpp -DYAML_CPP_BUILD_TESTS=OFF -DYAML_CPP_BUILD_TOOLS=OFF -DYAML_CPP_BUILD_CONTRIB=OFF
  build marisa-trie -DBUILD_TESTING=OFF -DENABLE_TOOLS=OFF
  # Cross compilation must not execute target dictionary generators. The frontend
  # bundles a simplified dictionary and does not require OpenCC data or tools.
  python3 - "$WORK/librime/deps/opencc/CMakeLists.txt" <<'PY'
import sys
p=sys.argv[1]
s=open(p).read().replace('add_subdirectory(data)', '# Android: no target data generator').replace('add_subdirectory(doc)', '# Android: no docs')
open(p,'w').write(s)
PY
  build opencc -DENABLE_GTEST=OFF -DBUILD_DOCUMENTATION=OFF -DUSE_SYSTEM_MARISA=ON -DCMAKE_PREFIX_PATH="$PREFIX"
  cmake -S "$WORK/librime" -B "$WORK/build/$ABI/rime" "${COMMON[@]}" -DBUILD_TEST=OFF -DBUILD_STATIC=ON -DBUILD_SHARED_LIBS=OFF -DBUILD_DATA=OFF -DENABLE_LOGGING=OFF -DENABLE_EXTERNAL_PLUGINS=OFF -DBoost_INCLUDE_DIR="$WORK/boost_1_85_0" -DCMAKE_PREFIX_PATH="$PREFIX"
  cmake --build "$WORK/build/$ABI/rime" --target install --parallel 3
  git -C "$WORK/librime" rev-parse HEAD
  git -C "$WORK/librime" submodule status
 done
