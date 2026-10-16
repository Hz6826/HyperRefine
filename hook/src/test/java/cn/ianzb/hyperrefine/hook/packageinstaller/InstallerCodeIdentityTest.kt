package cn.ianzb.hyperrefine.hook.packageinstaller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class InstallerCodeIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val code = linkedMapOf(
        "classes.dex" to byteArrayOf(1, 2, 3),
        "classes2.dex" to byteArrayOf(4, 5, 6),
    )
    private val verifier = InstallerCodeIdentity()

    @Test fun sameDexDifferentApkPackagingIsAccepted() {
        val first = archive(code + ("resources.arsc" to byteArrayOf(10)))
        val second = archive(code + ("resources.arsc" to byteArrayOf(20, 30)))
        assertNotEquals(digest(first), digest(second))
        assertTrue(inspect(first).readable)
        assertTrue(inspect(second).readable)
    }

    @Test fun changedDexProducesDiagnosticFingerprintWithoutAnAllowlist() {
        val apk = archive(code + ("classes2.dex" to byteArrayOf(4, 5, 7)))
        assertTrue(inspect(apk).readable)
        assertNotEquals(inspect(archive(code)).diagnostic, inspect(apk).diagnostic)
    }

    @Test fun additionalDexIsFingerprinted() {
        val result = inspect(archive(code + ("classes3.dex" to byteArrayOf(9))))
        assertTrue(result.readable)
        assertTrue(result.diagnostic.contains("classes3.dex="))
    }

    @Test fun singleDexIsAlsoFingerprinted() {
        assertTrue(inspect(archive(code.filterKeys { it == "classes.dex" })).readable)
    }

    @Test fun emptyDexSetHasNoFingerprint() {
        assertFalse(inspect(archive(mapOf("resources.arsc" to byteArrayOf(10)))).readable)
    }

    @Test fun duplicateDexCannotHideBehindTheLastZipEntry() {
        val apk = archive(code + ("classes.aux" to byteArrayOf(9)))
        // Equal-length ZIP header names: keep the valid archive structure and create a duplicate name.
        val renamed = apk.readBytes().toString(Charsets.ISO_8859_1)
            .replace("classes.aux", "classes.dex").toByteArray(Charsets.ISO_8859_1)
        apk.writeBytes(renamed)
        val result = inspect(apk)
        assertFalse(result.readable)
        assertTrue(result.diagnostic.contains("duplicate"))
    }

    private fun inspect(file: File): InstallerCodeIdentity.Result = ZipFile(file).use(verifier::inspect)
    private fun digest(file: File): String = file.inputStream().use(InstallerCodeIdentity::sha256)

    private fun archive(entries: Map<String, ByteArray>): File = temporary.newFile().apply {
        ZipOutputStream(outputStream()).use { zip ->
            entries.forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
    }
}
