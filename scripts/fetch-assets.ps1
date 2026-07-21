<#
.SYNOPSIS
    Fetches the proot binary + loader helpers CoDebian needs at build time
    and places them, renamed, under app/src/main/jniLibs/arm64-v8a/.

.DESCRIPTION
    Sources Termux's own `proot` .deb package -- the same binary Termux
    itself uses, so it's proven to work under Android's SELinux policy on
    real devices (built against Bionic, not glibc/musl). Verified while
    writing this script: the aarch64 .deb from
    https://mirrors.aliyun.com/termux/termux-main/pool/main/p/proot/ is an
    `ar` archive containing `data.tar.xz`, which in turn contains
    `.../usr/bin/proot` (ELF64, e_machine=183/EM_AARCH64) plus
    `.../usr/libexec/proot/{loader,loader32}` -- proot execs these helper
    binaries itself at runtime (see ProotRuntime.kt) instead of relying on
    a direct ptrace path, so both must ship alongside the main binary.

    Uses only `tar.exe` (bsdtar, bundled with Windows 10+, links against
    liblzma so it reads both the outer `ar`-format .deb *and* the inner
    xz-compressed tarball directly) -- no extra tools, no Python required.

.PARAMETER prootDebUrl
    Override if you want to pin a different proot version/mirror. Defaults
    to the aarch64 build verified above.
#>

param(
    [string]$prootDebUrl = "https://grimler.se/termux-packages-24/pool/main/p/proot/proot_5.1.107.86_aarch64.deb"
)

$ErrorActionPreference = "Stop"

$jniLibsDir = Join-Path $PSScriptRoot "..\app\src\main\jniLibs\arm64-v8a"
New-Item -ItemType Directory -Path $jniLibsDir -Force | Out-Null

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("codebian-proot-" + [Guid]::NewGuid())
New-Item -ItemType Directory -Path $work -Force | Out-Null
try {
    $deb = Join-Path $work "proot.deb"
    
    # Use curl with retry logic for robust downloads
    Write-Host "Downloading $prootDebUrl ..."
    curl.exe --fail --location --retry 3 --retry-delay 5 --retry-max-time 60 `
        -o $deb $prootDebUrl
    if ($LASTEXITCODE -ne 0) { throw "curl failed to download $prootDebUrl" }

    Write-Host "Extracting .deb (ar format) ..."
    tar -xf $deb -C $work
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
    tar -xf $dataTar -C $extractDir @members
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
