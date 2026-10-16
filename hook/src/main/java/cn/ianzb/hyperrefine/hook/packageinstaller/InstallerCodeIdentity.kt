package cn.ianzb.hyperrefine.hook.packageinstaller

import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Component fingerprints for diagnostics only. No version or hash allowlist. */
class InstallerCodeIdentity {
    data class Result(val readable: Boolean, val diagnostic: String)

    fun inspect(apk: ZipFile): Result {
        val entries = apk.entries().asSequence().filter { ROOT_DEX.matches(it.name) }.toList()
        if (entries.isEmpty()) return Result(false, "no primary DEX entries")
        if (entries.map { it.name }.distinct().size != entries.size) return Result(false, "duplicate primary DEX entries")
        val actual = entries.associate { entry -> entry.name to apk.getInputStream(entry).use(::sha256) }
        return Result(true, actual.toSortedMap().entries.joinToString { "${it.key}=${it.value}" })
    }

    companion object {
        private val ROOT_DEX = Regex("classes(?:[0-9]+)?\\.dex")
        internal fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
