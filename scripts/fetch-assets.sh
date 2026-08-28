#!/usr/bin/env bash
set -euo pipefail

# Fetches every pinned upstream binary CoDebian bundles at build time: the
# proot binary + loader helpers (app/src/main/jniLibs/arm64-v8a/, required),
# plus the Debian rootfs layer and code-server .deb (app/src/main/assets/,
# best-effort -- see below). All versions/URLs come from scripts/versions.json,
# the single source of truth also read by app/build.gradle.kts's
# :app:generateRemoteAssets task -- bump versions there, not in this script.
#
# proot is required: without it the app cannot run at all, so a failure
# fetching it fails this whole script. The rootfs/code-server bundling is
# best-effort: BootstrapService falls back to its original live-download
# behavior for whichever didn't get bundled (or if this script is skipped
# entirely, e.g. a quick local dev build) -- the app still works without
# them, it just needs network on first run.
#
# Usage: scripts/fetch-assets.sh [proot-deb-url]

scriptDir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repoRoot="$(cd "$scriptDir/.." && pwd)"
versionsJson="$scriptDir/versions.json"

if ! command -v python3 >/dev/null 2>&1; then
  echo "python3 is required (used to parse scripts/versions.json and JSON API responses)" >&2
  exit 1
fi

jsonGet() {
  # jsonGet <dotted.path.through.versions.json>
  python3 -c '
import json, sys
with open(sys.argv[1]) as f:
    d = json.load(f)
for key in sys.argv[2].split("."):
    d = d[key]
print(d)
' "$versionsJson" "$1"
}

sha256Hex() {
  python3 -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$1"
}

prootDebUrl="${1:-$(jsonGet proot.debUrl)}"
jniLibsDir="$repoRoot/app/src/main/jniLibs/arm64-v8a"
assetsDir="$repoRoot/app/src/main/assets"
mkdir -p "$jniLibsDir" "$assetsDir"

fetch_proot() {
  echo "=== proot ($(jsonGet proot.version)) ==="
  local workdir deb extracted prefix
  workdir="$(mktemp -d)"
  trap 'rm -rf "$workdir"' RETURN

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
}

# --- Docker Registry HTTP API v2 client, just enough to pull a single
# architecture's rootfs layer for library/debian from Docker Hub -- a
# shell/python mirror of DockerRegistryClient.kt's logic, kept
# deliberately in sync with it (same registry, same anonymous-pull token
# dance, same arch/variant matching).
docker_hub_token() {
  # docker_hub_token <repository>
  curl -fsSL "https://auth.docker.io/token?service=registry.docker.io&scope=repository:$1:pull" \
    | python3 -c 'import json, sys; print(json.load(sys.stdin)["token"])'
}

fetch_rootfs() {
  local repo tag arch variant token digest layerDigest layerSize destName dest actualHex expectedHex blobToken
  repo="$(jsonGet debianRootfs.repository)"
  tag="$(jsonGet debianRootfs.tag)"
  arch="$(jsonGet debianRootfs.arch)"
  variant="$(jsonGet debianRootfs.variant)"
  echo "=== Debian rootfs ($repo:$tag $arch/$variant) ==="

  token="$(docker_hub_token "$repo")"
  digest="$(curl -fsSL \
    -H "Authorization: Bearer $token" \
    -H "Accept: application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.oci.image.index.v1+json" \
    "https://registry-1.docker.io/v2/$repo/manifests/$tag" \
    | python3 -c "
import json, sys
d = json.load(sys.stdin)
for m in d['manifests']:
    p = m['platform']
    if p['architecture'] == '$arch' and p.get('variant') == '$variant':
        print(m['digest'])
        break
")"
  if [ -z "$digest" ]; then
    echo "No manifest for $repo:$tag matching arch=$arch variant=$variant" >&2
    return 1
  fi

  layerDigest="$(curl -fsSL \
    -H "Authorization: Bearer $token" \
    -H "Accept: application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json" \
    "https://registry-1.docker.io/v2/$repo/manifests/$digest" \
    | python3 -c "
import json, sys
d = json.load(sys.stdin)
layers = d['layers']
assert len(layers) == 1, f'expected a single-layer rootfs image, got {len(layers)} layers'
assert layers[0]['mediaType'].endswith('tar+gzip'), f'unexpected layer mediaType {layers[0][\"mediaType\"]!r}'
print(layers[0]['digest'])
")"

  # Deliberately not "*.tar.gz" -- see the ROOTFS_ASSET_NAME comment in
  # app/build.gradle.kts for why AGP's asset packaging would silently
  # mangle that name/content combination.
  destName="rootfs-$tag-$arch.tar.gz.packed"
  dest="$assetsDir/$destName"
  echo "Downloading layer $layerDigest ..."
  # Re-fetch a token for the actual blob download -- tokens are short-
  # lived and resolving the manifest above may have taken a moment.
  blobToken="$(docker_hub_token "$repo")"
  curl -fSL --retry 3 --retry-delay 5 \
    -H "Authorization: Bearer $blobToken" \
    -o "$dest" "https://registry-1.docker.io/v2/$repo/blobs/$layerDigest"

  actualHex="$(sha256Hex "$dest")"
  expectedHex="${layerDigest#sha256:}"
  if [ "$actualHex" != "$expectedHex" ]; then
    rm -f "$dest"
    echo "Downloaded rootfs layer failed SHA-256 verification (expected $expectedHex, got $actualHex)" >&2
    return 1
  fi
  printf '%s' "$actualHex" > "$dest.sha256"
  echo "Wrote $destName (+ .sha256) to $assetsDir"
}

fetch_code_server() {
  local version url expectedHex destName dest actualHex
  version="$(jsonGet codeServer.version)"
  url="$(jsonGet codeServer.debUrl)"
  expectedHex="$(jsonGet codeServer.sha256)"
  echo "=== code-server ($version, arm64) ==="

  destName="code-server-$version-arm64.deb"
  dest="$assetsDir/$destName"
  echo "Downloading $url ..."
  curl -fSL --retry 3 --retry-delay 5 -o "$dest" "$url"

  actualHex="$(sha256Hex "$dest")"
  if [ "$actualHex" != "$expectedHex" ]; then
    rm -f "$dest"
    echo "Downloaded code-server .deb failed SHA-256 verification (expected $expectedHex, got $actualHex) -- if you intentionally bumped codeServer.version in scripts/versions.json, update codeServer.sha256 to match the new release's asset digest" >&2
    return 1
  fi
  echo "Wrote $destName to $assetsDir"
}

# proot is required.
fetch_proot

# Rootfs/code-server bundling is best-effort -- see header comment.
failures=()
fetch_rootfs || failures+=("rootfs")
fetch_code_server || failures+=("code-server")
if [ "${#failures[@]}" -gt 0 ]; then
  echo "Warning: continuing without bundling: ${failures[*]} -- BootstrapService will fall back to downloading these at first app run instead." >&2
fi
