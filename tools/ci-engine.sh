#!/bin/bash
# Build librime and the OpenCC dictionaries on the CI runner, then leave them
# where Gradle already looks. Nothing produced here is committed.
#
#   third_party/librime/include/rime_api.h
#   third_party/librime/jniLibs/<abi>/librime.so
#   app/src/main/assets/rime/shared/opencc/*.ocd2
#
# Pins: librime 1.17.0 (BSD-3-Clause, public C API), OpenCC 1.4.2 (Apache-2.0),
# Boost 1.84.0, yaml-cpp 0.8.0, LevelDB 1.23, marisa from the OpenCC tree.
# Dictionaries are compiled on the little-endian host. Each Android ABI links
# those dependencies statically into one librime.so.
set -euo pipefail

ROOT=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
WORK=${FSK_WORK:-${RUNNER_TEMP:-/tmp}/fskeyboard-engine}
NDK_VERSION=27.2.12479018
API=26
ABIS=(arm64-v8a x86_64)
STAMP_NAME="librime-1.17.0 opencc-1.4.2 boost-1.84.0 yaml-cpp-0.8.0 leveldb-1.23 ndk-${NDK_VERSION} api-${API}"

LIBRIME_URL="https://github.com/rime/librime/archive/refs/tags/1.17.0.tar.gz"
LIBRIME_SHA=a60274da5d8b8a7187e6c7e9ba5023334ed7bdd182535e93c4e96de8cf188377
OPENCC_URL="https://github.com/BYVoid/OpenCC/releases/download/ver.1.4.2/opencc-1.4.2-opencc-src.tar.gz"
OPENCC_SHA=164c19dc25217c1da98a13384f0ace5fb9fc570e7c2703072ffb715ecbace3e5
BOOST_URL="https://archives.boost.io/release/1.84.0/source/boost_1_84_0.tar.bz2"
BOOST_SHA=cc4b893acf645c9d4b698e9a0f08ca8846aa5d6c68275c14c3e7949c24109454
YAML_URL="https://github.com/jbeder/yaml-cpp/archive/refs/tags/0.8.0.tar.gz"
YAML_SHA=fbe74bbdcee21d656715688706da3c8becfd946d92cd44705cc6098bb23b3a16
LEVELDB_URL="https://github.com/google/leveldb/archive/refs/tags/1.23.tar.gz"
LEVELDB_SHA=9a37f8a6174f09bd622bc723b55881dc541cd50747cbd08831c2a82d620f6d76
CMAKE_URL="https://github.com/Kitware/CMake/releases/download/v3.31.8/cmake-3.31.8-linux-x86_64.tar.gz"
CMAKE_SHA=630615d8e98ac33eba7fbe472626dff5c899c85af3c024585ae109166a6909d0

DICT_NAMES=(
  CJK_Compatibility_Ideographs.ocd2
  STPhrases.ocd2
  STPhrases_GeneratedFromRegionalPhrases.ocd2
  STCharacters.ocd2
  TWVariantsPhrases.ocd2
  TWVariants.ocd2
)
OPENCC_ASSET="$ROOT/app/src/main/assets/rime/shared/opencc"
RIME_OUT="$ROOT/third_party/librime"
STAMP_FILE="$RIME_OUT/jniLibs/.ci-stamp"

sha256_file() {
  python3 - "$1" << 'PY'
import hashlib, sys
print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())
PY
}

fetch() {
  local url=$1 sha=$2 dest=$3
  if [[ -f "$dest" ]] && [[ "$(sha256_file "$dest")" == "$sha" ]]; then
    return
  fi
  echo "fetch $url"
  curl -fL --retry 3 --retry-delay 2 -o "$dest.partial" "$url"
  local got
  got=$(sha256_file "$dest.partial")
  if [[ "$got" != "$sha" ]]; then
    echo "checksum mismatch for $url" >&2
    echo "expected $sha" >&2
    echo "actual   $got" >&2
    exit 1
  fi
  mv "$dest.partial" "$dest"
}

extract() {
  local archive=$1 dest=$2
  rm -rf "$dest"
  mkdir -p "$dest"
  tar -xf "$archive" -C "$dest" --strip-components=1
}

outputs_ready() {
  [[ -f "$STAMP_FILE" ]] || return 1
  [[ "$(cat "$STAMP_FILE")" == "$STAMP_NAME" ]] || return 1
  [[ -f "$RIME_OUT/include/rime_api.h" ]] || return 1
  local abi name
  for abi in "${ABIS[@]}"; do
    [[ -f "$RIME_OUT/jniLibs/$abi/librime.so" ]] || return 1
  done
  for name in "${DICT_NAMES[@]}"; do
    [[ -s "$OPENCC_ASSET/$name" ]] || return 1
  done
}

require_little_endian() {
  python3 - << 'PY'
import sys
if sys.byteorder != "little":
    raise SystemExit("OpenCC .ocd2 must be built on a little-endian host")
PY
}

find_ndk() {
  local candidate
  if [[ -n "${ANDROID_NDK_HOME:-}" && -f "${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake" ]]; then
    echo "$ANDROID_NDK_HOME"
    return
  fi
  if [[ -n "${ANDROID_NDK_ROOT:-}" && -f "${ANDROID_NDK_ROOT}/build/cmake/android.toolchain.cmake" ]]; then
    echo "$ANDROID_NDK_ROOT"
    return
  fi
  if [[ -n "${ANDROID_HOME:-}" && -f "${ANDROID_HOME}/ndk/${NDK_VERSION}/build/cmake/android.toolchain.cmake" ]]; then
    echo "${ANDROID_HOME}/ndk/${NDK_VERSION}"
    return
  fi
  echo "NDK ${NDK_VERSION} not found. Set ANDROID_NDK_HOME or ANDROID_HOME." >&2
  exit 1
}

must_replace() {
  local path=$1 old=$2 new=$3 label=$4
  python3 - "$path" "$old" "$new" "$label" << 'PY'
import pathlib, sys
path, old, new, label = sys.argv[1:]
file = pathlib.Path(path)
text = file.read_text()
found = text.count(old)
if found != 1:
    raise SystemExit(f"{label}: expected 1 match in {path}, found {found}")
file.write_text(text.replace(old, new, 1))
PY
}

prepare_cmake() {
  local archive="$WORK/downloads/cmake.tar.gz"
  fetch "$CMAKE_URL" "$CMAKE_SHA" "$archive"
  if [[ ! -x "$WORK/cmake/bin/cmake" ]]; then
    rm -rf "$WORK/cmake"
    mkdir -p "$WORK/cmake"
    tar -xf "$archive" -C "$WORK/cmake" --strip-components=1
  fi
  CMAKE="$WORK/cmake/bin/cmake"
}

build_host_opencc() {
  local src="$WORK/src/opencc" build="$WORK/build/opencc-host" prefix="$WORK/host"
  extract "$WORK/downloads/opencc.tar.gz" "$src"
  rm -rf "$build" "$prefix"
  echo "host OpenCC (dictionaries)"
  "$CMAKE" -S "$src" -B "$build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_INSTALL_PREFIX="$prefix" \
    -DCMAKE_INSTALL_LIBDIR=lib \
    -DBUILD_SHARED_LIBS=OFF \
    -DENABLE_GTEST=OFF \
    -DENABLE_BENCHMARK=OFF \
    -DBUILD_PYTHON=OFF \
    -DBUILD_DOCUMENTATION=OFF \
    -DOPENCC_DICT_FORMAT=ocd2
  "$CMAKE" --build "$build" --parallel "$JOBS"
  "$CMAKE" --install "$build"
  sh "$ROOT/tools/install-shared-data.sh" "$prefix/share/opencc"
}

patch_opencc_for_android() {
  local src=$1
  must_replace "$src/CMakeLists.txt" \
    $'add_subdirectory(data)\n' \
    $'# dictionaries are built on the host; opencc_dict cannot run as an Android binary\n' \
    "opencc data"
  must_replace "$src/src/CMakeLists.txt" \
    $'add_subdirectory(tools)\n' \
    $'# CLI tools are not linked into librime\n' \
    "opencc tools"
}

build_boost_regex() {
  local abi=$1 prefix=$2 clang=$3 ar=$4
  local src="$WORK/src/boost" obj="$WORK/build/boost-$abi"
  if [[ ! -d "$src/libs/regex/src" ]]; then
    extract "$WORK/downloads/boost.tar.bz2" "$src"
  fi
  mkdir -p "$prefix/include" "$prefix/lib" "$obj"
  if [[ ! -f "$prefix/include/boost/version.hpp" ]]; then
    cp -a "$src/boost" "$prefix/include/"
  fi
  # Boost.Regex 1.84 is header-only for C++11 and later. librime still expects
  # the archive path to exist; nothing in it needs to be linked.
  if [[ ! -f "$prefix/lib/libboost_regex.a" ]]; then
    printf '%s\n' 'extern "C" void fsk_boost_regex_anchor() {}' > "$obj/anchor.cpp"
    "$clang" -c -fPIC -o "$obj/anchor.o" "$obj/anchor.cpp"
    "$ar" rcs "$prefix/lib/libboost_regex.a" "$obj/anchor.o"
  fi
}

set_android_cmake() {
  local abi=$1 prefix=$2
  ANDROID_CMAKE_ARGS=(
    -G Ninja
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
    -DANDROID_ABI="$abi"
    -DANDROID_PLATFORM="android-$API"
    -DANDROID_STL=c++_static
    -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON
    -DCMAKE_BUILD_TYPE=Release
    -DCMAKE_INSTALL_PREFIX="$prefix"
    -DCMAKE_INSTALL_LIBDIR=lib
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON
    -DFS_DEPS_PREFIX="$prefix"
    -DCMAKE_PROJECT_INCLUDE="$WORK/android-prefix.cmake"
    -DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384
  )
}

build_yaml() {
  local abi=$1 prefix=$2
  local src="$WORK/src/yaml-cpp" build="$WORK/build/yaml-$abi"
  if [[ ! -f "$src/CMakeLists.txt" ]]; then
    extract "$WORK/downloads/yaml.tar.gz" "$src"
  fi
  rm -rf "$build"
  set_android_cmake "$abi" "$prefix"
  "$CMAKE" -S "$src" -B "$build" "${ANDROID_CMAKE_ARGS[@]}" \
    -DBUILD_SHARED_LIBS=OFF \
    -DYAML_BUILD_SHARED_LIBS=OFF \
    -DYAML_CPP_BUILD_TESTS=OFF \
    -DYAML_CPP_BUILD_TOOLS=OFF \
    -DYAML_CPP_BUILD_CONTRIB=OFF \
    -DYAML_CPP_FORMAT_SOURCE=OFF \
    -DYAML_CPP_INSTALL=ON
  "$CMAKE" --build "$build" --parallel "$JOBS"
  "$CMAKE" --install "$build"
}

build_leveldb() {
  local abi=$1 prefix=$2
  local src="$WORK/src/leveldb" build="$WORK/build/leveldb-$abi"
  if [[ ! -f "$src/CMakeLists.txt" ]]; then
    extract "$WORK/downloads/leveldb.tar.gz" "$src"
  fi
  rm -rf "$build"
  set_android_cmake "$abi" "$prefix"
  "$CMAKE" -S "$src" -B "$build" "${ANDROID_CMAKE_ARGS[@]}" \
    -DBUILD_SHARED_LIBS=OFF \
    -DLEVELDB_BUILD_TESTS=OFF \
    -DLEVELDB_BUILD_BENCHMARKS=OFF \
    -DLEVELDB_INSTALL=ON \
    -DHAVE_CLANG_THREAD_SAFETY:BOOL=OFF
  "$CMAKE" --build "$build" --target leveldb --parallel "$JOBS"
  "$CMAKE" --install "$build"
}

build_opencc_android() {
  local abi=$1 prefix=$2
  local src="$WORK/src/opencc-android" build="$WORK/build/opencc-$abi"
  if [[ ! -f "$src/CMakeLists.txt" ]]; then
    extract "$WORK/downloads/opencc.tar.gz" "$src"
    patch_opencc_for_android "$src"
  fi
  rm -rf "$build"
  set_android_cmake "$abi" "$prefix"
  "$CMAKE" -S "$src" -B "$build" "${ANDROID_CMAKE_ARGS[@]}" \
    -DBUILD_SHARED_LIBS=OFF \
    -DENABLE_GTEST=OFF \
    -DENABLE_BENCHMARK=OFF \
    -DBUILD_PYTHON=OFF \
    -DBUILD_DOCUMENTATION=OFF \
    -DOPENCC_DICT_FORMAT=ocd2
  "$CMAKE" --build "$build" --target libopencc --parallel "$JOBS"
  "$CMAKE" --install "$build"
  mkdir -p "$prefix/include"
  cp -a "$src/deps/marisa-0.3.1/include/." "$prefix/include/"
}

patch_librime() {
  local src=$1
  must_replace "$src/CMakeLists.txt" \
    $'if(LINUX)\n  find_package(Boost 1.74.0 REQUIRED COMPONENTS regex)\nelse()\n  find_package(Boost 1.77.0)\nendif()\nif(Boost_FOUND)\n  include_directories(${Boost_INCLUDE_DIRS})\n  link_directories(${Boost_LIBRARY_DIRS})\n  add_definitions(-DBOOST_DLL_USE_STD_FS)\nendif()\n' \
    $'set(Boost_INCLUDE_DIRS "${FS_DEPS_PREFIX}/include")\nset(Boost_LIBRARY_DIRS "${FS_DEPS_PREFIX}/lib")\nset(Boost_LIBRARIES "${FS_DEPS_PREFIX}/lib/libboost_regex.a")\nif(NOT EXISTS "${Boost_LIBRARIES}")\n  message(FATAL_ERROR "missing ${Boost_LIBRARIES}")\nendif()\ninclude_directories(${Boost_INCLUDE_DIRS})\nlink_directories(${Boost_LIBRARY_DIRS})\nadd_definitions(-DBOOST_DLL_USE_STD_FS)\n' \
    "librime boost"
  must_replace "$src/CMakeLists.txt" \
    $'if(BUILD_SHARED_LIBS)\n  add_subdirectory(tools)\n' \
    $'if(BUILD_SHARED_LIBS)\n  # deployer and console are not packaged\n' \
    "librime tools"
  must_replace "$src/src/CMakeLists.txt" \
    $'  target_link_libraries(rime ${rime_deps})\n' \
    $'  target_link_libraries(rime -Wl,--start-group ${rime_deps} -Wl,--end-group dl m)\n' \
    "librime link"
  must_replace "$src/src/CMakeLists.txt" \
    $'  set_target_properties(rime PROPERTIES\n    DEFINE_SYMBOL "RIME_EXPORTS"\n    VERSION ${rime_version}\n    SOVERSION ${rime_soversion}\n' \
    $'  set_target_properties(rime PROPERTIES\n    DEFINE_SYMBOL "RIME_EXPORTS"\n' \
    "librime soname"
}

build_librime() {
  local abi=$1 prefix=$2
  local src="$WORK/src/librime" build="$WORK/build/librime-$abi"
  if [[ ! -f "$src/CMakeLists.txt" ]]; then
    extract "$WORK/downloads/librime.tar.gz" "$src"
    patch_librime "$src"
  fi
  mkdir -p "$prefix/include/X11"
  cp -a "$src/include/X11/." "$prefix/include/X11/"
  rm -rf "$build"
  set_android_cmake "$abi" "$prefix"
  "$CMAKE" -S "$src" -B "$build" "${ANDROID_CMAKE_ARGS[@]}" \
    -DBUILD_SHARED_LIBS=ON \
    -DBUILD_STATIC=ON \
    -DBUILD_TEST=OFF \
    -DBUILD_DATA=OFF \
    -DBUILD_SAMPLE=OFF \
    -DBUILD_SEPARATE_LIBS=OFF \
    -DENABLE_LOGGING=OFF \
    -DENABLE_ASAN=OFF \
    -DENABLE_EXTERNAL_PLUGINS=OFF
  "$CMAKE" --build "$build" --target rime --parallel "$JOBS"
  local so="$build/lib/librime.so"
  if [[ ! -f "$so" ]]; then
    echo "librime.so missing for $abi" >&2
    exit 1
  fi
  mkdir -p "$RIME_OUT/jniLibs/$abi"
  cp -L "$so" "$RIME_OUT/jniLibs/$abi/librime.so"
  if [[ ! -f "$RIME_OUT/include/rime_api.h" ]]; then
    mkdir -p "$RIME_OUT/include"
    cp "$src/src/rime_api.h" "$RIME_OUT/include/rime_api.h"
  fi
}

verify_so() {
  local abi=$1
  local so="$RIME_OUT/jniLibs/$abi/librime.so"
  if ! nm -D "$so" | grep -q ' rime_get_api$'; then
    echo "$abi: rime_get_api is not exported" >&2
    nm -D "$so" | grep rime_get_api || true
    exit 1
  fi
  local soname
  soname=$(readelf -d "$so" | awk -F'[][]' '/SONAME/ {print $2}')
  if [[ "$soname" != "librime.so" ]]; then
    echo "$abi: SONAME is '$soname', expected librime.so" >&2
    exit 1
  fi
  local needed extra=0 name
  needed=$(readelf -d "$so" | awk -F'[][]' '/NEEDED/ {print $2}')
  while IFS= read -r name; do
    [[ -z "$name" ]] && continue
    case "$name" in
      libc.so|libm.so|libdl.so|liblog.so) ;;
      *)
        echo "$abi: unexpected NEEDED $name" >&2
        extra=1
        ;;
    esac
  done <<< "$needed"
  if [[ "$extra" -ne 0 ]]; then
    readelf -d "$so" | grep NEEDED >&2 || true
    exit 1
  fi
  echo "$abi NEEDED:"
  echo "$needed"
}

clang_for() {
  local abi=$1
  local triple
  case "$abi" in
    arm64-v8a) triple=aarch64-linux-android ;;
    x86_64) triple=x86_64-linux-android ;;
    *) echo "unsupported ABI $abi" >&2; exit 1 ;;
  esac
  echo "$PREBUILT/bin/${triple}${API}-clang++"
}

if outputs_ready; then
  echo "librime and OpenCC dictionaries already match this script"
  exit 0
fi

require_little_endian
command -v python3 >/dev/null
command -v curl >/dev/null
command -v ninja >/dev/null
command -v g++ >/dev/null
command -v nm >/dev/null
command -v readelf >/dev/null

NDK=$(find_ndk)
PREBUILT=$(echo "$NDK"/toolchains/llvm/prebuilt/*)
if [[ ! -d "$PREBUILT/bin" ]]; then
  echo "NDK llvm prebuilt not found under $NDK" >&2
  exit 1
fi
JOBS=$(nproc 2>/dev/null || echo 4)

mkdir -p "$WORK/downloads" "$WORK/src" "$WORK/build"
cat > "$WORK/android-prefix.cmake" << 'EOF'
# The Android toolchain only searches CMAKE_FIND_ROOT_PATH. Append the prefix
# that holds the static libraries built for this ABI.
if(DEFINED FS_DEPS_PREFIX AND NOT FS_DEPS_PREFIX IN_LIST CMAKE_FIND_ROOT_PATH)
  list(APPEND CMAKE_FIND_ROOT_PATH "${FS_DEPS_PREFIX}")
endif()
EOF

prepare_cmake
fetch "$OPENCC_URL" "$OPENCC_SHA" "$WORK/downloads/opencc.tar.gz"
fetch "$LIBRIME_URL" "$LIBRIME_SHA" "$WORK/downloads/librime.tar.gz"
fetch "$BOOST_URL" "$BOOST_SHA" "$WORK/downloads/boost.tar.bz2"
fetch "$YAML_URL" "$YAML_SHA" "$WORK/downloads/yaml.tar.gz"
fetch "$LEVELDB_URL" "$LEVELDB_SHA" "$WORK/downloads/leveldb.tar.gz"
rm -rf "$WORK/src" "$WORK/build" "$WORK/prefix"

rm -f "$RIME_OUT/include/rime_api.h" "$STAMP_FILE"
local_abi=""
for local_abi in "${ABIS[@]}"; do
  rm -f "$RIME_OUT/jniLibs/$local_abi/librime.so"
done
for local_abi in "${DICT_NAMES[@]}"; do
  rm -f "$OPENCC_ASSET/$local_abi"
done

build_host_opencc

for local_abi in "${ABIS[@]}"; do
  prefix="$WORK/prefix/$local_abi"
  rm -rf "$prefix"
  mkdir -p "$prefix"
  echo "ABI $local_abi"
  clang=$(clang_for "$local_abi")
  ar="$PREBUILT/bin/llvm-ar"
  build_boost_regex "$local_abi" "$prefix" "$clang" "$ar"
  build_yaml "$local_abi" "$prefix"
  build_leveldb "$local_abi" "$prefix"
  build_opencc_android "$local_abi" "$prefix"
  build_librime "$local_abi" "$prefix"
  verify_so "$local_abi"
done

printf '%s\n' "$STAMP_NAME" > "$STAMP_FILE"
echo "packaged librime and OpenCC dictionaries"
