<#
.SYNOPSIS
    Fetches every pinned upstream binary CoDebian bundles at build time:
    the proot binary + loader helpers (app/src/main/jniLibs/arm64-v8a/,
    required), plus the Debian rootfs layer and code-server .deb
    (app/src/main/assets/, best-effort -- see below).

.DESCRIPTION
    All versions/URLs come from scripts/versions.json, the single source
    of truth also read by app/build.gradle.kts's :app:generateRemoteAssets
    task (which turns it into dev.codebian.app.RemoteAssets) -- bump
    versions there, not in this script.

    proot: sources Termux's own `proot` .deb package -- the same binary
    Termux itself uses, so it's proven to work under Android's SELinux
    policy on real devices (built against Bionic, not glibc/musl).
    Verified while writing this script: the aarch64 .deb from
    https://mirrors.aliyun.com/termux/termux-main/pool/main/p/proot/ is an
    `ar` archive containing `data.tar.xz`, which in turn contains
    `.../usr/bin/proot` (ELF64, e_machine=183/EM_AARCH64) plus
    `.../usr/libexec/proot/{loader,loader32}` -- proot execs these helper
    binaries itself at runtime (see ProotRuntime.kt) instead of relying on
    a direct ptrace path, so both must ship alongside the main binary.
    This one is required: without it the app cannot function at all, so a
    failure here fails the whole script.

    Debian rootfs / code-server: pulled the same way BootstrapService
    itself would at first run (Docker Registry HTTP API v2 for the
    rootfs layer; a direct GitHub release download for code-server),
    sha256-verified, and written into app/src/main/assets/ so first launch
    needs no network access for them and isn't affected by the live
    `trixie` tag moving on or a future code-server release replacing the
    exact file this build was tested against. These two are best-effort:
    if fetching either fails (or this script is skipped entirely, e.g. a
    quick local dev build), BootstrapService falls back to its original
    live-download behavior -- unlike proot, the app still works without
    them, just needs network on first run.

.PARAMETER prootDebUrl
    Override if you want to pin a different proot version/mirror. Defaults
    to the value from scripts/versions.json.
#>

param(
    [string]$prootDebUrl = $null
)

$ErrorActionPreference = "Stop"

$repoRoot = Join-Path $PSScriptRoot ".."
$versionsJson = Get-Content (Join-Path $PSScriptRoot "versions.json") -Raw | ConvertFrom-Json
if (-not $prootDebUrl) { $prootDebUrl = $versionsJson.proot.debUrl }

$jniLibsDir = Join-Path $repoRoot "app\src\main\jniLibs\arm64-v8a"
$assetsDir = Join-Path $repoRoot "app\src\main\assets"
New-Item -ItemType Directory -Path $jniLibsDir -Force | Out-Null
New-Item -ItemType Directory -Path $assetsDir -Force | Out-Null

function Get-Sha256Hex([string]$path) {
    (Get-FileHash -Path $path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Fetch-Proot {
    Write-Host "=== proot ($($versionsJson.proot.version)) ==="
    $work = Join-Path ([System.IO.Path]::GetTempPath()) ("codebian-proot-" + [Guid]::NewGuid())
    New-Item -ItemType Directory -Path $work -Force | Out-Null
    try {
        $deb = Join-Path $work "proot.deb"

        Write-Host "Downloading $prootDebUrl ..."
        curl.exe --fail --location --retry 3 --retry-delay 5 --retry-max-time 60 `
            -o $deb $prootDebUrl
        if ($LASTEXITCODE -ne 0) { throw "curl failed to download $prootDebUrl" }

        Write-Host "Extracting .deb (ar format) ..."
        # Windows' own bsdtar (System32\tar.exe) reads the outer `ar` container
        # fine; it's only the inner xz stream its liblzma build chokes on
        # (see below).
        & "$env:SystemRoot\System32\tar.exe" -xf $deb -C $work
        if ($LASTEXITCODE -ne 0) { throw "tar failed to read $deb as an ar archive" }

        $dataTar = Join-Path $work "data.tar.xz"
        if (-not (Test-Path $dataTar)) { throw "data.tar.xz not found inside $deb -- .deb layout may have changed" }

        Write-Host "Extracting proot + loader binaries from data.tar.xz ..."
        $extractDir = Join-Path $work "extracted"
        New-Item -ItemType Directory -Path $extractDir -Force | Out-Null
        $members = @(
            "./data/data/com.termux/files/usr/bin/proot",
            "./data/data/com.termux/files/usr/libexec/proot/loader",
            "./data/data/com.termux/files/usr/libexec/proot/loader32"
        )
        # Git for Windows' bundled GNU tar (unlike the Windows-provided
        # System32\tar.exe used above, whose liblzma build errors out on this
        # particular xz stream with "Corrupted input data") reads xz fine, and
        # extracting only the members we need sidesteps a harmless but
        # exit-code-tainting broken doc symlink elsewhere in the archive.
        # --force-local keeps it from misreading the "C:\..." path as a
        # remote host (GNU tar's ssh-style archive syntax uses ':').
        # Resolved explicitly (rather than relying on $env:ProgramFiles, which
        # can point at the x86 Program Files under a 32-bit PowerShell host)
        # since Git for Windows itself is only ever installed 64-bit.
        $gitTarCandidates = @(
            "C:\Program Files\Git\usr\bin\tar.exe",
            "C:\Program Files (x86)\Git\usr\bin\tar.exe"
        )
        $gitTar = $gitTarCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
        if (-not $gitTar) { throw "Git for Windows tar not found (checked: $($gitTarCandidates -join ', ')) -- required to extract data.tar.xz (install Git for Windows, or adjust this script)" }
        & $gitTar --force-local -xf $dataTar -C $extractDir @members
        if ($LASTEXITCODE -ne 0) { throw "tar failed to extract members from data.tar.xz -- .deb layout may have changed" }

        $prefix = "$extractDir/data/data/com.termux/files/usr"
        Copy-Item "$prefix/bin/proot" (Join-Path $jniLibsDir "libproot.so") -Force
        Copy-Item "$prefix/libexec/proot/loader" (Join-Path $jniLibsDir "libproot-loader.so") -Force
        Copy-Item "$prefix/libexec/proot/loader32" (Join-Path $jniLibsDir "libproot-loader32.so") -Force

        Write-Host "Wrote libproot.so, libproot-loader.so, libproot-loader32.so to $jniLibsDir"
    }
    finally {
        Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# --- Docker Registry HTTP API v2 client, just enough to pull a single
# architecture's rootfs layer for library/debian from Docker Hub -- a
# PowerShell mirror of DockerRegistryClient.kt's logic, kept deliberately
# in sync with it (same registry, same anonymous-pull token dance, same
# arch/variant matching).
function Get-DockerHubToken([string]$repository) {
    $uri = "https://auth.docker.io/token?service=registry.docker.io&scope=repository:${repository}:pull"
    (Invoke-RestMethod -Uri $uri).token
}

function Fetch-Rootfs {
    $repo = $versionsJson.debianRootfs.repository
    $tag = $versionsJson.debianRootfs.tag
    $arch = $versionsJson.debianRootfs.arch
    $variant = $versionsJson.debianRootfs.variant
    Write-Host "=== Debian rootfs ($repo`:$tag $arch/$variant) ==="

    $token = Get-DockerHubToken $repo
    $headers = @{ Authorization = "Bearer $token" }

    $indexAccept = "application/vnd.docker.distribution.manifest.list.v2+json,application/vnd.oci.image.index.v1+json"
    $index = Invoke-RestMethod -Uri "https://registry-1.docker.io/v2/$repo/manifests/$tag" `
        -Headers ($headers + @{ Accept = $indexAccept })

    $digest = $index.manifests | Where-Object {
        $_.platform.architecture -eq $arch -and $_.platform.variant -eq $variant
    } | Select-Object -First 1 -ExpandProperty digest
    if (-not $digest) { throw "No manifest for $repo`:$tag matching arch=$arch variant=$variant" }

    $manifestAccept = "application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json"
    $manifest = Invoke-RestMethod -Uri "https://registry-1.docker.io/v2/$repo/manifests/$digest" `
        -Headers ($headers + @{ Accept = $manifestAccept })
    if ($manifest.layers.Count -ne 1) { throw "Expected a single-layer rootfs image, got $($manifest.layers.Count) layers" }
    $layer = $manifest.layers[0]
    if (-not $layer.mediaType.EndsWith("tar+gzip")) { throw "Unexpected layer mediaType $($layer.mediaType)" }

    # Deliberately not "*.tar.gz" -- see the ROOTFS_ASSET_NAME comment in
    # app/build.gradle.kts for why AGP's asset packaging would silently
    # mangle that name/content combination.
    $destName = "rootfs-$tag-$arch.tar.gz.packed"
    $dest = Join-Path $assetsDir $destName
    Write-Host "Downloading layer $($layer.digest) ($([math]::Round($layer.size / 1MB, 1)) MiB) ..."
    # Re-fetch a token for the actual blob download -- tokens are short-
    # lived and resolving the manifest above may have taken a moment.
    $blobToken = Get-DockerHubToken $repo
    curl.exe --fail --location --retry 3 --retry-delay 5 --retry-max-time 300 `
        -H "Authorization: Bearer $blobToken" `
        -o $dest "https://registry-1.docker.io/v2/$repo/blobs/$($layer.digest)"
    if ($LASTEXITCODE -ne 0) { throw "curl failed to download rootfs layer $($layer.digest)" }

    $actualHex = Get-Sha256Hex $dest
    $expectedHex = $layer.digest -replace '^sha256:', ''
    if ($actualHex -ne $expectedHex) {
        Remove-Item $dest -Force -ErrorAction SilentlyContinue
        throw "Downloaded rootfs layer failed SHA-256 verification (expected $expectedHex, got $actualHex)"
    }
    Set-Content -Path "$dest.sha256" -Value $actualHex -NoNewline
    Write-Host "Wrote $destName (+ .sha256) to $assetsDir"
}

function Fetch-CodeServer {
    $version = $versionsJson.codeServer.version
    $url = $versionsJson.codeServer.debUrl
    $expectedHex = $versionsJson.codeServer.sha256
    Write-Host "=== code-server ($version, arm64) ==="

    $destName = "code-server-$version-arm64.deb"
    $dest = Join-Path $assetsDir $destName
    Write-Host "Downloading $url ..."
    curl.exe --fail --location --retry 3 --retry-delay 5 --retry-max-time 300 `
        -o $dest $url
    if ($LASTEXITCODE -ne 0) { throw "curl failed to download $url" }

    $actualHex = Get-Sha256Hex $dest
    if ($actualHex -ne $expectedHex) {
        Remove-Item $dest -Force -ErrorAction SilentlyContinue
        throw "Downloaded code-server .deb failed SHA-256 verification (expected $expectedHex, got $actualHex) -- if you intentionally bumped codeServer.version in scripts/versions.json, update codeServer.sha256 to match the new release's asset digest"
    }
    Write-Host "Wrote $destName to $assetsDir"
}

# proot is required -- the app cannot run at all without it, so let a
# failure here fail the whole script/build (as before this script grew
# the two best-effort fetches below).
Fetch-Proot

# Rootfs/code-server bundling is best-effort: BootstrapService falls back
# to its original live-download behavior for whichever of these didn't
# get bundled, so one of these failing (e.g. a transient network blip, or
# a genuinely dead URL after a version bump that needs scripts/versions.json
# fixed up) shouldn't also block the other or fail CI over what is, for
# now, just a nice-to-have that keeps first-run offline-capable.
$failures = @()
try { Fetch-Rootfs } catch { Write-Warning "Fetch-Rootfs failed: $_"; $failures += "rootfs" }
try { Fetch-CodeServer } catch { Write-Warning "Fetch-CodeServer failed: $_"; $failures += "code-server" }
if ($failures.Count -gt 0) {
    Write-Warning "Continuing without bundling: $($failures -join ', ') -- BootstrapService will fall back to downloading these at first app run instead."
}
