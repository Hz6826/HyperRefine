package cn.ianzb.hyperrefine.hook.packageinstaller

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generated DEX bytes are parsed only; no fixture or ROM class is executed. */
class InstallerNullReturnProofTest {
    @Test fun exactReturnRegisterMustBeZero() {
        assertTrue(proof(listOf(0x0012, 0x0011)).proven)
        assertFalse(proof(listOf(0x0112, 0x0011)).proven)
        assertFalse(proof(listOf(0x0112, 0x001a, 0, 0x0011)).proven)
    }

    @Test fun zeroMovesAreTrackedAcrossAllOrdinaryEncodings() {
        for (move in listOf(listOf(0x0107), listOf(0x0108, 0), listOf(0x0009, 1, 0),
            listOf(0x0101), listOf(0x0102, 0), listOf(0x0003, 1, 0))) {
            assertTrue(proof(listOf(0x0012) + move + 0x0111).proven)
        }
    }

    @Test fun otherRegisterWritesKillTheNullFact() {
        assertFalse(proof(listOf(0x0012, 0x001a, 0, 0x0011)).proven)
        assertFalse(proof(listOf(0x0012, 0x107b, 0x0011)).proven) // neg-int v0,v1
        assertFalse(proof(listOf(0x0012, 0x1071, 0, 1, 0x000c, 0x0011)).proven) // move-result-object
        assertFalse(proof(listOf(0x0112, 0x0018, 0, 0, 0, 0, 0x0111)).proven) // wide v0 kills v1
        assertFalse(proof(listOf(0x0112, 0x1004, 0x0111), registers = 3).proven) // move-wide v0,v1
    }

    @Test fun zeroConstantsAndCheckCastAreRecognizedWithoutMixingWideValues() {
        for (constant in listOf(listOf(0x0012), listOf(0x0013, 0), listOf(0x0014, 0, 0), listOf(0x0015, 0))) {
            assertTrue(proof(constant + listOf(0x001f, 1, 0x0011)).proven)
        }
        assertFalse(proof(listOf(0x0016, 0, 0x0011)).proven)
    }

    @Test fun impossibleKnownZeroAndNonzeroBranchesDoNotInventWitnesses() {
        // v0 is zero; if-nez cannot reach the otherwise valid null-return block.
        assertFalse(proof(listOf(0x0012, 0x0039, 5, 0x001a, 0, 0x0011, 0x0012, 0x0011)).proven)
        assertTrue(proof(listOf(0x0012, 0x0038, 5, 0x001a, 0, 0x0011, 0x0012, 0x0011)).proven)
        assertFalse(proof(listOf(0x001a, 0, 0x0038, 5, 0x001a, 0, 0x0011, 0x0012, 0x0011)).proven)
        // Same-register inequality is false, even when that input register is unknown.
        assertFalse(proof(listOf(0x1133, 5, 0x001a, 0, 0x0011, 0x0012, 0x0011)).proven)
    }

    @Test fun branchConstraintsAndAllIncomingStatesAreRespected() {
        // Both branches merge at return: one writes a string, so no must-null result survives.
        assertFalse(proof(listOf(0x0012, 0x0138, 5, 0x001a, 0, 0x0228, 0x0000, 0x0011)).proven)
        // The earlier ZERO fact must be revoked after the other path arrives.
        assertFalse(proof(listOf(0x0138, 5, 0x0012, 0x0428, 0x0000, 0x001a, 0, 0x0011)).proven)
        assertTrue(proof(listOf(0x0138, 4, 0x0012, 0x0228, 0x0012, 0x0011)).proven)
        // Unknown v1 becomes known zero on the if-eqz edge; nested if-nez is impossible there.
        assertFalse(proof(listOf(0x0138, 5, 0x001a, 0, 0x0011, 0x0139, 5,
            0x001a, 0, 0x0011, 0x0012, 0x0011)).proven)
    }

    @Test fun unreachableNullInstructionIsNotAProof() {
        assertFalse(proof(listOf(0x001a, 0, 0x0011, 0x0012, 0x0011)).proven)
    }

    @Test fun handlerUsesPreInstructionStateAndMoveExceptionKillsItsDestination() {
        // v0 was null before an allocation that may throw; handler returns the old v0.
        val units = listOf(0x0012, 0x0022, 1, 0x0011, 0x010d, 0x0011)
        assertTrue(proof(units, tries = listOf(Try(1, 3, 4))).proven)
        assertFalse(proof(units.toMutableList().apply { this[4] = 0x000d }, tries = listOf(Try(1, 3, 4))).proven)
        // An impossible exception edge from NOP must not make the catch's zero-return reachable.
        assertFalse(proof(listOf(0x0000, 0x001a, 0, 0x0011, 0x010d, 0x0012, 0x0011),
            tries = listOf(Try(0, 1, 4))).proven)
    }

    @Test fun handlerJoinsCannotKeepOneProtectedPathNullAndIgnoreAnother() {
        val units = listOf(0x0012, 0x0022, 1, 0x1071, 0, 1, 0x0011, 0x010d, 0x0011)
        assertFalse(proof(units, tries = listOf(Try(1, 6, 7))).proven)
    }

    @Test fun packedAndSparseSwitchCasesAreRealControlFlowEdges() {
        for (payload in listOf(listOf(0x0100, 1, 0, 0, 5, 0), listOf(0x0200, 1, 0, 0, 5, 0))) {
            val opcode = if (payload.first() == 0x0100) 0x012b else 0x012c
            val good = listOf(0x0012, opcode, 7, 0, 0x0000, 0x0228, 0x0012, 0x0011) + payload
            assertTrue(proof(good).proven)
            // Default supplies an unknown input while case supplies zero; merge must be unknown.
            assertFalse(proof(good.toMutableList().apply { this[4] = 0x1007 }).proven)
        }
    }

    @Test fun fillArrayDataHasExceptionEdgesEvenWhenHistoricalOpcodeFlagOmitsThem() {
        val units = listOf(0x0012, 0x0112, 0x0126, 8, 0, 0x001a, 0, 0x0011,
            0x010d, 0x0011, 0x0300, 1, 1, 0, 1)
        assertTrue(proof(units, tries = listOf(Try(2, 5, 8))).proven)
    }

    @Test fun payloadWordsCannotMasqueradeAsExecutableNullReturn() {
        assertFalse(proof(listOf(0x001a, 0, 0x0011, 0, 0x0300, 2, 2, 0, 0x0012, 0x0011)).proven)
        assertFalse(proof(listOf(0x0128, 0x0300, 1, 1, 0, 0)).proven)
        assertFalse(proof(listOf(0x0012, 0x0228, 0x0013, 0, 0x0011)).proven) // jump into const operand
    }

    @Test fun malformedAndUnknownInstructionsFailTheWholeMethodClosed() {
        for (units in listOf(listOf(0x0012, 0x0011, 0x00e3), listOf(0x0014, 0),
            listOf(0x0012, 0x0211), listOf(0x0012, 0x7f28), listOf(0x0400), listOf(0x0018, 0, 0, 0, 0, 0x0011))) {
            assertFalse(proof(units, registers = 1).proven)
        }
        assertFalse(proof(listOf(0x0012, 0x0011), tries = listOf(Try(1, 2, 99))).proven)
    }

    @Test fun instructionAndCodeBudgetsFailClosed() {
        assertFalse(proof(List(4_096) { 0 } + listOf(0x0012, 0x0011)).proven)
        assertFalse(proof(List(8_193) { 0 }).proven)
    }

    @Test fun checksumVersionTruncationAndDescriptorMismatchFailClosed() {
        val dex = dex(listOf(0x0012, 0x0011))
        assertFalse(InstallerNullReturnProof.proveDex(ByteArray(112), DESC).proven)
        assertFalse(InstallerNullReturnProof.proveDex(dex.copyOf(dex.size - 1), DESC).proven)
        assertFalse(InstallerNullReturnProof.proveDex(dex.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }, DESC).proven)
        assertFalse(InstallerNullReturnProof.proveDex(dex.copyOf().apply { this[6] = '1'.code.toByte(); refresh(this) }, DESC).proven)
        assertFalse(InstallerNullReturnProof.proveDex(dex, DESC.replace("value", "other")).proven)
        assertFalse(InstallerNullReturnProof.proveDex(dex, DESC.replace("Ljava/lang/Object;", "Ljava/lang/String;")).proven)
    }

    @Test fun apkReaderCachesOnlyExactDescriptorsAndEmitsBoundedStaticDiagnostics() {
        withApk(mapOf("classes.dex" to dex(listOf(0x0012, 0x0011)))) { path ->
            val proof = InstallerNullReturnProof(path)
            assertTrue(proof.hasNullReturn(DESC)); assertTrue(proof.hasNullReturn(DESC))
            assertEquals(1, proof.diagnosticLines().size)
            assertTrue(proof.diagnosticLines().all { it.length <= 384 && path !in it && "must_null_return" in it })
        }
        withApk(mapOf("classes.dex" to dex(listOf(0x0012, 0x0011)), "classes10.dex" to dex(listOf(0x0012, 0x0011)))) { path ->
            assertFalse(InstallerNullReturnProof(path).hasNullReturn(DESC))
        }
        withApk(mapOf("classes.dex" to byteArrayOf(0))) { path ->
            assertFalse(InstallerNullReturnProof(path).hasNullReturn(DESC))
        }
        assertFalse(InstallerNullReturnProof("this-file-does-not-exist.apk").hasNullReturn(DESC))
    }

    private data class Try(val start: Int, val end: Int, val handler: Int)
    private fun proof(units: List<Int>, registers: Int = 2, tries: List<Try> = emptyList()) =
        InstallerNullReturnProof.proveDex(dex(units, registers, tries), DESC)

    /** Minimal standalone classic DEX with ordinary tables, code_item, class_data and map_list. */
    private fun dex(units: List<Int>, registers: Int = 2, tries: List<Try> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()
        fun u1(value: Int) { out.write(value and 255) }
        fun u2(value: Int) { u1(value); u1(value ushr 8) }
        fun u4(value: Int) { u2(value); u2(value ushr 16) }
        fun leb(value: Int) { var rest = value; do { val byte = rest and 127; rest = rest ushr 7; u1(byte or if (rest == 0) 0 else 128) } while (rest != 0) }
        fun align() { while (out.size() % 4 != 0) u1(0) }
        repeat(188) { u1(0) }
        val stringOffsets = listOf("LL", "Lfixture/Target;", "Ljava/lang/Object;", "value").map { text ->
            out.size().also { leb(text.length); out.write(text.toByteArray(Charsets.UTF_8)); u1(0) }
        }
        align(); val parameters = out.size(); u4(1); u2(1); u2(0)
        align(); val code = out.size()
        u2(registers); u2(1); u2(1); u2(tries.size); u4(0); u4(units.size); units.forEach(::u2)
        if (tries.isNotEmpty()) {
            if (units.size % 2 != 0) u2(0)
            // One catch-all per unique handler; handler_off is relative to the encoded list start.
            val unique = tries.map { it.handler }.distinct()
            val handlerBytes = ByteArrayOutputStream()
            fun handlerLeb(value: Int) { var rest = value; do { val byte = rest and 127; rest = rest ushr 7; handlerBytes.write(byte or if (rest == 0) 0 else 128) } while (rest != 0) }
            handlerLeb(unique.size)
            val offsets = unique.associateWith { handler -> handlerBytes.size().also { handlerBytes.write(0); handlerLeb(handler) } }
            tries.forEach { u4(it.start); u2(it.end - it.start); u2(offsets.getValue(it.handler)) }
            out.write(handlerBytes.toByteArray())
        }
        val classData = out.size(); listOf(0, 0, 1, 0, 0, 9, code).forEach(::leb)
        align(); val map = out.size()
        val maps = listOf(Triple(0, 1, 0), Triple(1, 4, 112), Triple(2, 2, 128), Triple(3, 1, 136),
            Triple(5, 1, 148), Triple(6, 1, 156), Triple(0x2002, 4, stringOffsets.first()),
            Triple(0x1001, 1, parameters), Triple(0x2001, 1, code), Triple(0x2000, 1, classData), Triple(0x1000, 1, map))
        u4(maps.size); maps.forEach { (type, count, offset) -> u2(type); u2(0); u4(count); u4(offset) }
        val bytes = out.toByteArray(); val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        "dex\n039\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        buffer.putInt(32, bytes.size); buffer.putInt(36, 112); buffer.putInt(40, 0x12345678); buffer.putInt(52, map)
        buffer.putInt(56, 4); buffer.putInt(60, 112); buffer.putInt(64, 2); buffer.putInt(68, 128)
        buffer.putInt(72, 1); buffer.putInt(76, 136); buffer.putInt(88, 1); buffer.putInt(92, 148)
        buffer.putInt(96, 1); buffer.putInt(100, 156); buffer.putInt(104, bytes.size - 188); buffer.putInt(108, 188)
        stringOffsets.forEachIndexed { i, at -> buffer.putInt(112 + i * 4, at) }
        buffer.putInt(128, 1); buffer.putInt(132, 2); buffer.putInt(136, 0); buffer.putInt(140, 1); buffer.putInt(144, parameters)
        buffer.putShort(148, 0); buffer.putShort(150, 0); buffer.putInt(152, 3)
        buffer.putInt(156, 0); buffer.putInt(160, 17); buffer.putInt(164, 1); buffer.putInt(172, -1); buffer.putInt(180, classData)
        refresh(bytes); return bytes
    }
    private fun refresh(bytes: ByteArray) {
        MessageDigest.getInstance("SHA-1").digest(bytes.copyOfRange(32, bytes.size)).copyInto(bytes, 12)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(8, Adler32().apply { update(bytes, 12, bytes.size - 12) }.value.toInt())
    }
    private fun withApk(entries: Map<String, ByteArray>, test: (String) -> Unit) {
        val directory = java.nio.file.Paths.get("build", "installer-null-proof-tests").toAbsolutePath()
        Files.createDirectories(directory)
        val path = Files.createTempFile(directory, "installer-null-proof-", ".apk")
        try {
            ZipOutputStream(Files.newOutputStream(path)).use { zip -> entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            } }
            test(path.toString())
        } finally { Files.deleteIfExists(path) }
    }
    companion object { private const val DESC = "Lfixture/Target;->value(Ljava/lang/Object;)Ljava/lang/Object;" }
}
