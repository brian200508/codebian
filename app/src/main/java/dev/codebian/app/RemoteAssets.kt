package dev.codebian.app

/**
 * Pins the exact upstream image this app bootstraps at first run.
 *
 * Fetched via [DockerRegistryClient] from Docker Hub's registry, the same
 * `debian:trixie` image debuerreotype publishes and proot-distro itself
 * pulls -- verified reachable and checksum-correct while wiring this up
 * (arm64/v8 manifest sha256:8ac748152418b19ff289badbf878c42561c5b0cd922ad
 * e5fe4fa37cf0769b521). `trixie` (Debian 13) is current stable as of this
 * writing; `bookworm` (Debian 12) moved to oldstable/LTS-only in June 2026.
 * Using the registry directly (rather than a static tarball URL) means
 * this keeps working as the `trixie` tag is republished, without needing
 * to re-pin a URL by hand. The earlier idea of pulling debuerreotype's
 * `rootfs.tar.xz` straight from GitHub was a dead end: that file is a
 * git-lfs pointer, not real content, when fetched via
 * raw.githubusercontent.com.
 */
object RemoteAssets {
    const val DEBIAN_REPOSITORY: String = "library/debian"
    const val DEBIAN_TAG: String = "trixie"
    const val DEBIAN_ARCH: String = "arm64"
    const val DEBIAN_VARIANT: String = "v8"
}
