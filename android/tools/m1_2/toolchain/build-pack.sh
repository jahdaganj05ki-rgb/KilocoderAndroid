#!/usr/bin/env bash
# M1.2 toolchain pack — host side.
#
# This runs only on the GitHub Actions Ubuntu runner. It downloads the pinned
# sources, boots the arm64 Debian rootfs under qemu-user, installs JDK 17,
# Gradle and the Android SDK, runs the guest checks, and packs the result as
# rootfs.tar.zst. The app itself is never built here.
set -euo pipefail

SELF=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
REPO=$(cd "$SELF/../../../.." && pwd)
WORK=${TOOLCHAIN_WORK:-${RUNNER_TEMP:-/tmp}/kilocoder-toolchain}
DL="$WORK/downloads"
ROOT="$WORK/rootfs"
OUT="$WORK/out"
VERSION=${TOOLCHAIN_VERSION:-1}

ROOTFS_URL="https://github.com/termux/proot-distro/releases/download/v4.7.0/debian-bookworm-aarch64-pd-v4.7.0.tar.xz"
ROOTFS_SHA="4baa32280cc70b67e2c650777c1d974349f0cdf23afaabc305ad3bc6182b8df8"
ROOTFS_SIZE="42894344"

GRADLE_URL="https://services.gradle.org/distributions/gradle-8.14.3-bin.zip"
GRADLE_SHA="bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531"

PLATFORM_35_URL="https://dl.google.com/android/repository/platform-35_r02.zip"
PLATFORM_35_SHA="0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0"
PLATFORM_35_SIZE="64273788"

PLATFORM_36_URL="https://dl.google.com/android/repository/platform-36_r02.zip"
PLATFORM_36_SHA="37607369a28c5b640b3a7998868d45898ebcb777565a0e85f9acf36f29631d2e"
PLATFORM_36_SIZE="65878410"

SDK_TOOLS_URL="https://github.com/lzhiyong/android-sdk-tools/releases/download/35.0.2/android-sdk-tools-static-aarch64.zip"
SDK_TOOLS_SHA="db1cea2c4454d5f9c5a802646b2d1cf560b4ee7badbe23e51ab8e1881bb50fc2"
SDK_TOOLS_SIZE="14401170"

BUILD_TOOLS_URL="https://dl.google.com/android/repository/build-tools_r35_linux.zip"
BUILD_TOOLS_SHA="bd3a4966912eb8b30ed0d00b0cda6b6543b949d5ffe00bea54c04c81e1561d88"
BUILD_TOOLS_SIZE="61958799"

log() { printf '\n== %s ==\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

fetch() {
    local url=$1 sha=$2 size=$3 dest=$4
    mkdir -p "$(dirname "$dest")"
    if [ -f "$dest" ]; then
        local have
        have=$(sha256sum "$dest" | cut -d' ' -f1)
        if [ "$have" = "$sha" ]; then
            log "reuse $(basename "$dest")"
            return
        fi
    fi
    log "download $(basename "$dest")"
    curl -fL --retry 4 --retry-delay 3 --connect-timeout 30 --max-time 600 \
        -o "$dest.part" "$url" || die "download failed: $url"
    local got bytes
    got=$(sha256sum "$dest.part" | cut -d' ' -f1)
    [ "$got" = "$sha" ] || {
        rm -f "$dest.part"
        die "SHA-256 mismatch for $(basename "$dest"): $got"
    }
    if [ "$size" != "-" ]; then
        bytes=$(stat -c %s "$dest.part")
        [ "$bytes" = "$size" ] || {
            rm -f "$dest.part"
            die "size mismatch for $(basename "$dest"): $bytes != $size"
        }
    fi
    mv "$dest.part" "$dest"
}

log "host prerequisites"
sudo apt-get update -qq
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
    qemu-user-static zstd unzip python3 >/dev/null

if [ ! -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ]; then
    sudo systemctl restart systemd-binfmt 2>/dev/null || true
fi
if [ ! -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ] && command -v docker >/dev/null 2>&1; then
    log "register arm64 binfmt"
    docker run --privileged --rm tonistiigi/binfmt --install arm64
fi
[ -e /proc/sys/fs/binfmt_misc/qemu-aarch64 ] || die "arm64 binfmt is not registered"

fetch "$ROOTFS_URL" "$ROOTFS_SHA" "$ROOTFS_SIZE" "$DL/rootfs.tar.xz"
fetch "$GRADLE_URL" "$GRADLE_SHA" "-" "$DL/gradle-8.14.3-bin.zip"
fetch "$PLATFORM_35_URL" "$PLATFORM_35_SHA" "$PLATFORM_35_SIZE" "$DL/platform-35.zip"
fetch "$PLATFORM_36_URL" "$PLATFORM_36_SHA" "$PLATFORM_36_SIZE" "$DL/platform-36.zip"
fetch "$SDK_TOOLS_URL" "$SDK_TOOLS_SHA" "$SDK_TOOLS_SIZE" "$DL/sdk-tools.zip"
fetch "$BUILD_TOOLS_URL" "$BUILD_TOOLS_SHA" "$BUILD_TOOLS_SIZE" "$DL/build-tools.zip"

if [ ! -d "$ROOT" ]; then
    log "extract rootfs"
    rm -rf "$WORK/rootfs-extract"
    mkdir -p "$WORK/rootfs-extract"
    # The proot-distro archive contains /dev device nodes. The runner user
    # cannot mknod them, and the chroot later bind-mounts the host /dev anyway.
    tar --no-same-owner --exclude='*/dev/*' -xJf "$DL/rootfs.tar.xz" -C "$WORK/rootfs-extract"
    mapfile -t top < <(find "$WORK/rootfs-extract" -mindepth 1 -maxdepth 1 -printf '%f\n')
    if [ "${#top[@]}" -eq 1 ] && [ -d "$WORK/rootfs-extract/${top[0]}" ]; then
        mv "$WORK/rootfs-extract/${top[0]}" "$ROOT"
    else
        mv "$WORK/rootfs-extract" "$ROOT"
    fi
    rmdir "$WORK/rootfs-extract" 2>/dev/null || true
fi

unmount_all() {
    set +e
    sudo umount -lf "$ROOT/dev/pts" 2>/dev/null
    sudo umount -lf "$ROOT/dev" 2>/dev/null
    sudo umount -lf "$ROOT/sys" 2>/dev/null
    sudo umount -lf "$ROOT/proc" 2>/dev/null
}
trap unmount_all EXIT

log "mount rootfs"
sudo mount -t proc proc "$ROOT/proc"
sudo mount -t sysfs sysfs "$ROOT/sys"
sudo mount --bind /dev "$ROOT/dev"
sudo mount --bind /dev/pts "$ROOT/dev/pts"

mkdir -p "$OUT" "$ROOT/tmp/toolchain-input" "$ROOT/opt/kilocoder"
sudo cp "$DL"/*.zip "$ROOT/tmp/toolchain-input/"
sudo install -m 0755 "$SELF/build-in-chroot.sh" "$ROOT/tmp/toolchain-input/build-in-chroot.sh"
sudo install -m 0755 /usr/bin/qemu-aarch64-static "$ROOT/usr/bin/qemu-aarch64-static" 2>/dev/null || true
sudo rm -f "$ROOT/etc/resolv.conf"
printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\noptions timeout:2 attempts:2\n' |
    sudo tee "$ROOT/etc/resolv.conf" >/dev/null

log "build inside arm64 rootfs"
sudo chroot "$ROOT" /usr/bin/env -i \
    HOME=/root \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    TOOLCHAIN_VERSION="$VERSION" \
    /bin/bash /tmp/toolchain-input/build-in-chroot.sh 2>&1 | tee "$WORK/chroot.log"

unmount_all
trap - EXIT

sudo cp "$ROOT/opt/kilocoder/toolchain.env" "$OUT/toolchain.env"
sudo cp "$ROOT/opt/kilocoder/toolchain-report.txt" "$OUT/toolchain-report.txt"
rm -rf "$OUT/evidence"
sudo cp -a "$ROOT/opt/kilocoder/evidence" "$OUT/evidence"

log "copy PRoot reference libraries"
sudo mkdir -p "$ROOT/opt/kilocoder/proot"
for name in libproot.so libproot-loader.so libtalloc.so libandroid-shmem.so; do
    sudo cp "$REPO/android/app/src/debug/jniLibs/arm64-v8a/$name" "$ROOT/opt/kilocoder/proot/$name"
done

log "ELF 16-KB check"
set +e
python3 "$SELF/check-elf.py" \
    "$ROOT" \
    "$ROOT/opt/kilocoder/proot" \
    "$OUT/elf-report.txt" \
    "$OUT/elf-report.json" | tee "$OUT/elf-summary.txt"
elf_status=${PIPESTATUS[0]}
set -e

log "pack rootfs.tar.zst"
sudo tar --numeric-owner --owner=0 --group=0 --sort=name \
    --mtime='2026-11-03 00:00:00Z' \
    --exclude='./proc/*' --exclude='./sys/*' --exclude='./dev/*' --exclude='./tmp/*' \
    -C "$ROOT" -cf - . |
    zstd -19 --long -T0 -q -o "$OUT/rootfs.tar.zst"

ROOTFS_TAR_SHA=$(sha256sum "$OUT/rootfs.tar.zst" | cut -d' ' -f1)
ROOTFS_TAR_SIZE=$(stat -c %s "$OUT/rootfs.tar.zst")
UNPACKED=$(sudo du -sb "$ROOT" | cut -f1)
GRADLE_SIZE=$(stat -c %s "$DL/gradle-8.14.3-bin.zip")

export ROOTFS_URL ROOTFS_SHA ROOTFS_SIZE
export GRADLE_URL GRADLE_SHA GRADLE_SIZE
export PLATFORM_35_URL PLATFORM_35_SHA PLATFORM_35_SIZE
export PLATFORM_36_URL PLATFORM_36_SHA PLATFORM_36_SIZE
export SDK_TOOLS_URL SDK_TOOLS_SHA SDK_TOOLS_SIZE
export BUILD_TOOLS_URL BUILD_TOOLS_SHA BUILD_TOOLS_SIZE

python3 "$SELF/write-manifest.py" \
    "$OUT" "$VERSION" "$UNPACKED" "$ROOTFS_TAR_SHA" "$ROOTFS_TAR_SIZE"

(cd "$OUT" && sha256sum rootfs.tar.zst manifest.json >SHA256SUMS)

log "artifacts"
ls -lh "$OUT"
cat "$OUT/manifest.json"

if [ "$elf_status" -ne 0 ]; then
    die "ELF 16-KB gate failed; see $OUT/elf-report.txt"
fi

ZIPALIGN_CHECK=$(grep '^ZIPALIGN_CHECK=' "$OUT/toolchain.env" | cut -d= -f2- || true)
if [ "$ZIPALIGN_CHECK" != "ok" ]; then
    die "zipalign -P 16 check failed; see $OUT/toolchain-report.txt"
fi
