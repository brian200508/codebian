#!/usr/bin/env bash
set -euo pipefail

# Usage: scripts/fetch-assets.sh [proot-deb-url]
prootDebUrl="${1:-https://grimler.se/termux-packages-24/pool/main/p/proot/proot_5.1.107.87_aarch64.deb}"
jniLibsDir="$(pwd)/app/src/main/jniLibs/arm64-v8a"
mkdir -p "$jniLibsDir"

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

deb="$workdir/proot.deb"
echo "Downloading $prootDebUrl ..."
curl -fsSL --retry 3 --retry-delay 5 -o "$deb" "$prootDebUrl"

# Extract .deb. Prefer dpkg-deb if available (clean), otherwise fall back to ar+tar.
extracted="$workdir/extracted"
mkdir -p "$extracted"
if command -v dpkg-deb >/dev/null 2>&1; then
  echo "Extracting .deb with dpkg-deb ..."
  dpkg-deb -x "$deb" "$extracted"
else
  echo "dpkg-deb not found; extracting with ar + tar fallback ..."
  (cd "$workdir" && ar x "$deb")
  if [ ! -f "$workdir/data.tar.xz" ]; then
    echo "data.tar.xz not found inside .deb -- layout may have changed" >&2
    exit 1
  fi
  tar -xJf "$workdir/data.tar.xz" -C "$extracted"
fi

prefix="$extracted/data/data/com.termux/files/usr"
if [ ! -f "$prefix/bin/proot" ]; then
  echo "proot binary not found at expected location: $prefix/bin/proot" >&2
  ls -R "$extracted" | sed -n '1,200p'
  exit 1
fi

cp -f "$prefix/bin/proot"    "$jniLibsDir/libproot.so"
cp -f "$prefix/libexec/proot/loader"  "$jniLibsDir/libproot-loader.so"
cp -f "$prefix/libexec/proot/loader32" "$jniLibsDir/libproot-loader32.so"
chmod 0644 "$jniLibsDir"/* || true

echo "Wrote libproot.so, libproot-loader.so, libproot-loader32.so to $jniLibsDir"
