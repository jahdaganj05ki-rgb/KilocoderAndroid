#!/usr/bin/env bash
# M1.2 · PRoot, loader, talloc and libandroid-shmem with the Android NDK r28.
#
# Runs on GitHub Actions only. The script builds all four arm64 binaries from
# pinned sources, verifies their 16-KB ELF alignment and replaces the Termux
# copies in the debug jniLibs before Gradle assembles the APK.
# Details, sources and limits: tools/m1_2/BUILD.md
set -euo pipefail

PROOT_VERSION=5.1.107.102
PROOT_SHA256=30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852
TALLOC_VERSION=2.5.0
TALLOC_SHA256=912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007
SHMEM_VERSION=0.7
SHMEM_SHA256=1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867
API=26
PAGE_SIZE=16384

NDK="${ANDROID_NDK_HOME:?ANDROID_NDK_HOME is not set}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
READELF="$TOOLCHAIN/bin/llvm-readelf"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
JNI="$ROOT/app/src/debug/jniLibs/arm64-v8a"
WORK="${RUNNER_TEMP:-/tmp}/proot-ndk"
REPORT="$ROOT/app/build/proot-ndk/report.txt"
mkdir -p "$WORK" "$JNI" "$(dirname "$REPORT")"
: > "$REPORT"

log() { echo "$*" | tee -a "$REPORT"; }

fetch() {
    local url=$1 out=$2 sum=$3
    curl -fsSL --retry 4 --retry-delay 3 --retry-all-errors -o "$out" "$url"
    echo "$sum  $out" | sha256sum -c -
}

log "PRoot M1.2 build · NDK ${NDK##*/} · API $API · page size $PAGE_SIZE"

# talloc 2.5.0 (LGPL-3.0-or-later). The cross-answers file is the one the
# Termux libtalloc package uses for the same version.
cd "$WORK"
fetch "https://www.samba.org/ftp/talloc/talloc-${TALLOC_VERSION}.tar.gz" \
    "talloc-${TALLOC_VERSION}.tar.gz" "$TALLOC_SHA256"
tar xf "talloc-${TALLOC_VERSION}.tar.gz"
cd "talloc-${TALLOC_VERSION}"
cat > cross-answers.txt <<'EOF'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
EOF
CC="$CC" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
    CFLAGS="-O2 -fPIC" \
    LINKFLAGS="-Wl,-z,max-page-size=${PAGE_SIZE} -Wl,-z,common-page-size=${PAGE_SIZE}" \
    ./configure --prefix="$WORK/talloc-prefix" --disable-rpath --disable-python \
        --cross-compile --cross-answers=cross-answers.txt
make -j"$(nproc)"
make install

# libandroid-shmem 0.7 (BSD-3-Clause), including its sys/shm.h.
cd "$WORK"
fetch "https://github.com/termux/libandroid-shmem/archive/refs/tags/v${SHMEM_VERSION}.tar.gz" \
    "libandroid-shmem.tar.gz" "$SHMEM_SHA256"
tar xf libandroid-shmem.tar.gz
cd "libandroid-shmem-${SHMEM_VERSION}"
# Bionic's <paths.h> has no _PATH_TMP. Named SysV keys are not used in
# Phase 1; the glibc default only satisfies the compile-time reference.
make CC="$CC" \
    CFLAGS="-fpic -std=c11 -Wall -Wextra -O2 -D_PATH_TMP='\"/tmp/\"'" \
    LDFLAGS="-Wl,--version-script=exports.txt -Wl,-z,max-page-size=${PAGE_SIZE} -Wl,-z,common-page-size=${PAGE_SIZE}" \
    libandroid-shmem.so
make install DESTDIR="$WORK/shmem-prefix" PREFIX=/usr

# PRoot 5.1.107.102 (GPL-2.0-only). Loading the header path from the shmem
# prefix makes <sys/shm.h> resolve to the emulation instead of bionic's stub.
cd "$WORK"
# The pinned hash belongs to GitHub's zip archive (the download the Termux
# package pins); the .tar.gz has a different checksum.
fetch "https://github.com/termux/proot/archive/v${PROOT_VERSION}.zip" \
    "proot.zip" "$PROOT_SHA256"
unzip -q proot.zip
cd "proot-${PROOT_VERSION}"
export CC AR RANLIB STRIP
export CPPFLAGS="-DARG_MAX=131072 -DVERSION='\"${PROOT_VERSION}\"' -I$WORK/talloc-prefix/include -I$WORK/shmem-prefix/usr/include"
# NDK r28 turns implicit function declarations into errors. PRoot's older
# sources omit a few <string.h> includes; keep those as warnings.
export CFLAGS="-O2 -Wno-error=implicit-function-declaration"
export LDFLAGS="-L$WORK/talloc-prefix/lib -L$WORK/shmem-prefix/usr/lib -Wl,-z,max-page-size=${PAGE_SIZE} -Wl,-z,common-page-size=${PAGE_SIZE}"
export LOADER_LDFLAGS="-Wl,-z,max-page-size=${PAGE_SIZE} -Wl,-z,common-page-size=${PAGE_SIZE}"
make -C src -j"$(nproc)" PROOT_WITH_LIBANDROID_SHMEM=true V=1

install -m 0755 src/proot "$JNI/libproot.so"
install -m 0755 src/loader/loader "$JNI/libproot-loader.so"
install -m 0644 "$(find "$WORK/talloc-prefix/lib" -maxdepth 1 -type f -name 'libtalloc.so.*' -print -quit)" \
    "$JNI/libtalloc.so"
install -m 0644 "$WORK/shmem-prefix/usr/lib/libandroid-shmem.so" "$JNI/libandroid-shmem.so"

fail=0
log ""
log "ELF checks in $JNI"
for name in libproot.so libproot-loader.so libtalloc.so libandroid-shmem.so; do
    file="$JNI/$name"
    head=$("$READELF" -hW "$file")
    grep -q 'ELF64' <<<"$head" || { log "FAIL $name: not an ELF64 file"; fail=1; }
    grep -q 'AArch64' <<<"$head" || { log "FAIL $name: not an AArch64 file"; fail=1; }
    max=0
    while read -r value; do
        [ -n "$value" ] || continue
        value=$((value))
        if ((value > max)); then max=$value; fi
    done < <("$READELF" -lW "$file" | awk '$1 == "LOAD" { print $NF }')
    if ((max < PAGE_SIZE)); then
        log "FAIL $name: LOAD alignment 0x$(printf '%x' "$max") is below 0x4000"
        fail=1
    else
        log "ok   $name: aarch64, LOAD alignment 0x$(printf '%x' "$max")"
    fi
done

"$READELF" -lW "$JNI/libproot.so" | grep -q '/system/bin/linker64' \
    || { log "FAIL libproot.so: interpreter /system/bin/linker64 missing"; fail=1; }
"$READELF" -dW "$JNI/libproot.so" | grep -q 'libtalloc\.so\.2' \
    || { log "FAIL libproot.so: DT_NEEDED libtalloc.so.2 missing"; fail=1; }
"$READELF" -dW "$JNI/libproot.so" | grep -q 'libandroid-shmem\.so' \
    || { log "FAIL libproot.so: DT_NEEDED libandroid-shmem.so missing"; fail=1; }
"$READELF" -dW "$JNI/libtalloc.so" | grep -q 'SONAME.*libtalloc\.so\.2' \
    || { log "FAIL libtalloc.so: SONAME libtalloc.so.2 missing"; fail=1; }

log ""
log "SHA-256 and sizes"
( cd "$JNI" && sha256sum -b libproot.so libproot-loader.so libtalloc.so libandroid-shmem.so ) | tee -a "$REPORT"
( cd "$JNI" && stat -c '%n %s bytes' libproot.so libproot-loader.so libtalloc.so libandroid-shmem.so ) | tee -a "$REPORT"

if ((fail != 0)); then
    log ""
    log "FAILED"
    exit 1
fi
log ""
log "OK"
