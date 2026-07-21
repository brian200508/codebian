package dev.codebian.app

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Minimal Docker Registry HTTP API v2 client, just enough to pull a single
 * architecture's rootfs layer for `library/debian` from Docker Hub.
 *
 * Verified end-to-end against the real registry while building this class:
 * resolving the arm64/v8 manifest for a `library/debian` tag returns a
 * single `application/vnd.oci.image.layer.v1.tar+gzip` layer whose
 * downloaded bytes hash to exactly the digest the registry declares (spot
 * -checked against both `bookworm` and the now-pinned `trixie`, see
 * RemoteAssets.kt) -- this is the same rootfs debuerreotype/proot-distro
 * use, fetched from the authoritative source instead of a possibly-stale
 * static URL or a git-lfs-pointer file (docker-debian-artifacts' own
 * `rootfs.tar.xz` files are LFS pointers when fetched via
 * raw.githubusercontent.com, not real content -- a dead end this app
 * deliberately avoids).
 *
 * No image-index caching, no multi-layer images, no auth beyond the
 * anonymous pull token Docker Hub hands out for public images -- deliberately
 * as small as it can be while still being correct for `library/debian`.
 */
class DockerRegistryClient(
    private val registry: String = "registry-1.docker.io",
    private val authService: String = "auth.docker.io",
) {
    data class Layer(val digest: String, val mediaType: String, val size: Long)

    /** Resolves the single rootfs layer for [repository]:[tag] on [arch]/[variant]. */
    fun resolveLayer(repository: String, tag: String, arch: String, variant: String? = null): Layer {
        val token = fetchToken(repository)
        val indexJson = getJson(
            "https://$registry/v2/$repository/manifests/$tag", token,
            accept = "application/vnd.docker.distribution.manifest.list.v2+json," +
                "application/vnd.oci.image.index.v1+json",
        )
        val manifests = indexJson.getJSONArray("manifests")
        var digest: String? = null
        for (i in 0 until manifests.length()) {
            val entry = manifests.getJSONObject(i)
            val platform = entry.optJSONObject("platform") ?: continue
            val matchesArch = platform.optString("architecture") == arch
            val matchesVariant = variant == null || platform.optString("variant") == variant
            if (matchesArch && matchesVariant) {
                digest = entry.getString("digest")
                break
            }
        }
        checkNotNull(digest) { "No manifest for $repository:$tag matching arch=$arch variant=$variant" }

        val manifestJson = getJson(
            "https://$registry/v2/$repository/manifests/$digest", token,
            accept = "application/vnd.oci.image.manifest.v1+json," +
                "application/vnd.docker.distribution.manifest.v2+json",
        )
        val layers = manifestJson.getJSONArray("layers")
        check(layers.length() == 1) {
            "Expected a single-layer rootfs image, got ${layers.length()} layers -- " +
                "extend downloadLayer()/extraction to fold multiple layers if this ever changes."
        }
        val layer = layers.getJSONObject(0)
        return Layer(
            digest = layer.getString("digest"),
            mediaType = layer.getString("mediaType"),
            size = layer.optLong("size", -1),
        )
    }

    /** Streams [layer] to [dest], reporting 0-100 progress via [onProgress]. */
    fun downloadLayer(repository: String, layer: Layer, dest: File, onProgress: (Int) -> Unit) {
        val token = fetchToken(repository)
        val connection = URL("https://$registry/v2/$repository/blobs/${layer.digest}")
            .openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.instanceFollowRedirects = true
        connection.connect()
        val total = if (layer.size > 0) layer.size else connection.contentLengthLong
        connection.inputStream.use { input ->
            FileOutputStream(dest).use { output ->
                val buffer = ByteArray(64 * 1024)
                var readTotal = 0L
                var read: Int
                while (input.read(buffer).also { read = it } >= 0) {
                    output.write(buffer, 0, read)
                    readTotal += read
                    if (total > 0) onProgress(((readTotal * 100) / total).toInt())
                }
            }
        }
    }

    /**
     * Verifies [file]'s SHA-256 hash matches the registry-declared
     * `sha256:<hex>` [digest]. Play Store review looks favourably on apps
     * that validate downloaded, executed content rather than trusting the
     * network blindly -- this also guards against a corrupted/truncated
     * download silently producing a broken rootfs.
     */
    fun verifyDigest(file: File, digest: String): Boolean {
        val expectedHex = digest.substringAfter("sha256:", digest)
        val actualHex = MessageDigest.getInstance("SHA-256").let { md ->
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } >= 0) md.update(buffer, 0, read)
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }
        return actualHex.equals(expectedHex, ignoreCase = true)
    }

    private fun fetchToken(repository: String): String {
        val url = "https://$authService/token?service=registry.docker.io&scope=repository:$repository:pull"
        val json = getJson(url, token = null, accept = "application/json")
        return json.getString("token")
    }

    private fun getJson(url: String, token: String?, accept: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", accept)
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        connection.connect()
        val body = connection.inputStream.bufferedReader().readText()
        return JSONObject(body)
    }
}
