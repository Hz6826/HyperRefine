package cn.ianzb.hyperrefine.hook.packageinstaller

import java.io.InputStream
import java.util.zip.Adler32
import java.util.zip.ZipFile

/** Read-only, bounded evidence for one selected DEX method. Never loads a target class. */
internal class InstallerNullReturnProof(private val source: String) {
    internal data class Result(val proven: Boolean, val reason: String, val instructions: Int = 0,
        val edges: Int = 0, val registers: Int = 0, val dexFiles: Int = 0)

    private val results = linkedMapOf<String, Result>()
    private var dexFiles: List<ByteArray>? = null

    @Synchronized fun hasNullReturn(descriptor: String): Boolean {
        results[descriptor]?.let { return it.proven }
        val result = try {
            demand(results.size < 8, "request_budget")
            demand(validDescriptor(descriptor), "descriptor")
            val files = dexFiles ?: readApk().also { dexFiles = it }
            val matches = files.mapNotNull { Dex(it).selected(descriptor) }
            demand(matches.size <= 1, "duplicate_method")
            if (matches.isEmpty()) Result(false, "method_missing", dexFiles = files.size)
            else analyze(matches.single()).copy(dexFiles = files.size)
        } catch (failure: Rejected) {
            Result(false, failure.reason)
        } catch (_: Throwable) {
            Result(false, "read_or_analysis_failed")
        }
        if (results.size < 8) results[descriptor] = result
        return result.proven
    }

    @Synchronized fun diagnosticLines(): List<String> = results.entries.toList().takeLast(2).map { (descriptor, result) ->
        ("probe layout_null_proof detail=proven=${result.proven};reason=${result.reason};" +
            "insns=${result.instructions};edges=${result.edges};regs=${result.registers};dex=${result.dexFiles};" +
            "method=${descriptor.take(240)}").take(384)
    }

    private fun readApk(): List<ByteArray> = ZipFile(source).use { zip ->
        val entries = zip.entries()
        val dex = mutableListOf<java.util.zip.ZipEntry>()
        val names = mutableSetOf<String>()
        var visited = 0
        while (entries.hasMoreElements()) {
            demand(++visited <= 32_768, "zip_entry_budget")
            val entry = entries.nextElement()
            if (ROOT_DEX.matches(entry.name)) {
                demand(names.add(entry.name), "duplicate_root_dex")
                demand(!entry.isDirectory && entry.size in 112..MAX_DEX_BYTES.toLong(), "dex_size")
                dex += entry
                demand(dex.size <= 16, "dex_count_budget")
            }
        }
        demand(dex.isNotEmpty(), "no_root_dex")
        var total = 0L
        dex.sortedBy { it.name }.map { entry ->
            total += entry.size
            demand(total <= 96L * 1_024 * 1_024, "apk_dex_budget")
            zip.getInputStream(entry).use { it.boundedBytes(MAX_DEX_BYTES) }.also {
                demand(it.size.toLong() == entry.size, "dex_size")
            }
        }
    }

    private data class TryRange(val start: Int, val end: Int, val handlers: List<Int>)
    private data class Code(val units: IntArray, val registers: Int, val tries: List<TryRange>,
        val strings: Int, val types: Int, val fields: Int, val methods: Int)
    private data class Insn(val address: Int, val opcode: Int, val width: Int, val dest: Int?,
        val source: Int?, val zero: Boolean, val wide: Boolean, val canThrow: Boolean,
        val next: List<Int>, val payload: Boolean = false, val operands: List<Int> = emptyList(), val nonzero: Boolean = false)
    private class Rejected(val reason: String) : IllegalStateException()

    /** Classic standalone DEX 035/037-040 only. New container formats deliberately fail closed. */
    private class Dex(private val bytes: ByteArray) {
        private val size = bytes.size
        private val stringCount: Int
        private val stringOffset: Int
        private val typeCount: Int
        private val typeOffset: Int
        private val fieldCount: Int
        private val methodCount: Int
        private val methodOffset: Int
        private val protoCount: Int
        private val protoOffset: Int
        private val classCount: Int
        private val classOffset: Int
        private val dataOffset: Int
        private val strings = mutableMapOf<Int, String>()
        private val types = mutableMapOf<Int, String>()

        init {
            demand(size in 112..MAX_DEX_BYTES, "dex_size")
            demand(bytes.sliceArray(0..3).contentEquals(byteArrayOf(100, 101, 120, 10)) && bytes[7] == 0.toByte(), "dex_magic")
            demand(String(bytes, 4, 3, Charsets.US_ASCII) in setOf("035", "037", "038", "039", "040"), "dex_version")
            demand(u4(32) == size && u4(36) == 112 && uint(40) == 0x12345678L, "dex_header")
            val checksum = Adler32().apply { update(bytes, 12, size - 12) }.value
            demand(uint(8) == checksum, "dex_checksum")
            stringCount = u4(56); stringOffset = u4(60)
            typeCount = u4(64); typeOffset = u4(68)
            protoCount = u4(72); protoOffset = u4(76)
            fieldCount = u4(80); methodCount = u4(88); methodOffset = u4(92)
            classCount = u4(96); classOffset = u4(100); dataOffset = u4(108)
            demand(stringCount <= 262_144 && typeCount <= 65_535 && protoCount <= 65_535 &&
                fieldCount <= 65_535 && methodCount <= 65_535 && classCount <= 65_535, "table_budget")
            table(stringOffset, stringCount, 4); table(typeOffset, typeCount, 4)
            table(protoOffset, protoCount, 12); table(u4(84), fieldCount, 8)
            table(methodOffset, methodCount, 8); table(classOffset, classCount, 32)
            demand(dataOffset in 112..size && u4(104).toLong() + dataOffset == size.toLong(), "data_bounds")
        }

        private fun table(offset: Int, count: Int, width: Int) {
            if (count == 0) demand(offset == 0, "table_bounds")
            else { demand(offset >= 112 && offset % 4 == 0, "table_bounds"); range(offset, count.toLong() * width) }
        }
        private fun range(offset: Int, length: Long) {
            demand(offset >= 0 && length >= 0 && offset.toLong() + length <= size, "bounds")
        }
        private fun u2(at: Int): Int { range(at, 2); return (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) }
        private fun uint(at: Int): Long { range(at, 4); return (0..3).fold(0L) { value, i -> value or ((bytes[at + i].toLong() and 255) shl (8 * i)) } }
        private fun u4(at: Int): Int { val value = uint(at); demand(value <= Int.MAX_VALUE, "integer_range"); return value.toInt() }
        private inner class Cursor(var at: Int) {
            fun leb(): Int {
                var value = 0L
                repeat(5) { i ->
                    range(at, 1); val byte = bytes[at++].toInt() and 255
                    value = value or ((byte.toLong() and 127) shl (7 * i))
                    if (byte and 128 == 0) { demand(value <= Int.MAX_VALUE, "uleb_range"); return value.toInt() }
                }
                throw Rejected("uleb_range")
            }
            fun signedLeb(): Int {
                var value = 0L
                repeat(5) { i ->
                    range(at, 1); val byte = bytes[at++].toInt() and 255
                    value = value or ((byte.toLong() and 127) shl (7 * i))
                    if (byte and 128 == 0) {
                        val bits = 7 * (i + 1)
                        if (byte and 64 != 0) value = value or (-1L shl bits)
                        demand(value in Int.MIN_VALUE..Int.MAX_VALUE, "sleb_range")
                        return value.toInt()
                    }
                }
                throw Rejected("sleb_range")
            }
        }
        private fun string(index: Int): String = strings.getOrPut(index) {
            demand(index in 0 until stringCount, "string_index")
            val offset = u4(stringOffset + index * 4)
            demand(offset in dataOffset until size, "string_bounds")
            val cursor = Cursor(offset); val length = cursor.leb()
            demand(length <= 4_096, "string_budget")
            val result = StringBuilder(length)
            while (true) {
                range(cursor.at, 1); val byte = bytes[cursor.at++].toInt() and 255
                if (byte == 0) break
                val character = when {
                    byte in 1..127 -> byte
                    byte and 224 == 192 -> {
                        range(cursor.at, 1); val next = bytes[cursor.at++].toInt() and 255
                        demand(next and 192 == 128, "mutf8")
                        val value = ((byte and 31) shl 6) or (next and 63)
                        demand(value >= 128 || byte == 192 && next == 128, "mutf8")
                        value
                    }
                    byte and 240 == 224 -> {
                        range(cursor.at, 2); val first = bytes[cursor.at++].toInt() and 255; val second = bytes[cursor.at++].toInt() and 255
                        demand(first and 192 == 128 && second and 192 == 128, "mutf8")
                        val value = ((byte and 15) shl 12) or ((first and 63) shl 6) or (second and 63)
                        demand(value >= 2_048, "mutf8"); value
                    }
                    else -> throw Rejected("mutf8")
                }
                result.append(character.toChar()); demand(result.length <= length, "mutf8_length")
            }
            demand(result.length == length, "mutf8_length"); result.toString()
        }
        private fun type(index: Int): String = types.getOrPut(index) {
            demand(index in 0 until typeCount, "type_index"); string(u4(typeOffset + index * 4))
        }
        private fun method(index: Int): String {
            demand(index in 0 until methodCount, "method_index")
            val at = methodOffset + index * 8; val proto = u2(at + 2)
            demand(proto in 0 until protoCount, "proto_index")
            val protoAt = protoOffset + proto * 12; val parameters = u4(protoAt + 8)
            val args = if (parameters == 0) "" else {
                demand(parameters >= dataOffset && parameters % 4 == 0, "parameters_bounds")
                val count = u4(parameters); demand(count <= 256, "parameter_budget"); range(parameters + 4, count.toLong() * 2)
                (0 until count).joinToString("") { type(u2(parameters + 4 + it * 2)) }
            }
            return "${type(u2(at))}->${string(u4(at + 4))}($args)${type(u4(protoAt + 4))}"
        }
        fun selected(descriptor: String): Code? {
            val owner = descriptor.substringBefore(";->") + ";"
            var selectedData: Int? = null
            for (i in 0 until classCount) {
                val at = classOffset + i * 32
                if (type(u4(at)) == owner) {
                    demand(selectedData == null, "duplicate_class")
                    selectedData = u4(at + 24)
                }
            }
            val start = selectedData ?: return null
            if (start == 0) return null
            demand(start in dataOffset until size, "class_data_bounds")
            val cursor = Cursor(start)
            val staticFields = cursor.leb(); val instanceFields = cursor.leb()
            val directMethods = cursor.leb(); val virtualMethods = cursor.leb()
            demand(staticFields.toLong() + instanceFields <= 16_384 && directMethods.toLong() + virtualMethods <= 8_192, "class_data_budget")
            for (count in listOf(staticFields, instanceFields)) {
                var index = 0L
                repeat(count) { i -> val delta = cursor.leb(); demand(i == 0 || delta > 0, "field_order"); index += delta
                    demand(index < fieldCount, "field_index"); cursor.leb() }
            }
            var selectedCode: Int? = null
            var access = 0
            val seen = mutableSetOf<Int>()
            for (count in listOf(directMethods, virtualMethods)) {
                var index = 0L
                repeat(count) {
                    index += cursor.leb(); demand(index < methodCount && seen.add(index.toInt()), "method_order")
                    demand(type(u2(methodOffset + index.toInt() * 8)) == owner, "method_owner")
                    val flags = cursor.leb(); val code = cursor.leb()
                    if (method(index.toInt()) == descriptor) {
                        demand(selectedCode == null, "duplicate_method"); selectedCode = code; access = flags
                    }
                }
            }
            val code = selectedCode ?: return null
            demand(code != 0 && access and (0x100 or 0x400) == 0, "no_code")
            demand(code >= dataOffset && code % 4 == 0, "code_bounds")
            range(code, 16)
            val registers = u2(code); val inputs = u2(code + 2); val tries = u2(code + 6); val units = u4(code + 12)
            demand(registers in 1..512 && inputs <= registers && units in 1..8_192 && tries <= 128, "code_budget")
            val args = descriptor.substringAfter('(').substringBefore(')')
            var argumentAt = 0; var words = if (access and 8 == 0) 1 else 0
            while (argumentAt < args.length) {
                var array = false
                while (argumentAt < args.length && args[argumentAt] == '[') { array = true; argumentAt++ }
                demand(argumentAt < args.length, "parameter_type")
                val kind = args[argumentAt++]
                if (kind == 'L') {
                    val end = args.indexOf(';', argumentAt); demand(end >= argumentAt, "parameter_type"); argumentAt = end + 1
                } else demand(kind in "ZBSCIJFD", "parameter_type")
                words += if (!array && kind in "JD") 2 else 1
            }
            demand(inputs == words, "input_registers")
            range(code + 16, units.toLong() * 2)
            val instructions = IntArray(units) { u2(code + 16 + it * 2) }
            var after = code + 16 + units * 2
            if (tries > 0 && units % 2 != 0) { demand(u2(after) == 0, "code_padding"); after += 2 }
            range(after, tries.toLong() * 8)
            val ranges = if (tries == 0) emptyList() else {
                val handlersStart = after + tries * 8; val handlersCursor = Cursor(handlersStart)
                val count = handlersCursor.leb(); demand(count in 1..256, "handler_budget")
                val handlers = mutableMapOf<Int, List<Int>>()
                repeat(count) {
                    val offset = handlersCursor.at - handlersStart; val encoded = handlersCursor.signedLeb()
                    demand(encoded in -128..128, "handler_budget")
                    val addresses = mutableListOf<Int>()
                    repeat(kotlin.math.abs(encoded)) {
                        demand(handlersCursor.leb() < typeCount, "catch_type_index"); addresses += handlersCursor.leb()
                    }
                    if (encoded <= 0) addresses += handlersCursor.leb()
                    demand(addresses.all { it in 0 until units }, "handler_bounds"); handlers[offset] = addresses
                }
                var end = 0
                (0 until tries).map { i ->
                    val at = after + i * 8; val begin = u4(at); val length = u2(at + 4)
                    demand(length > 0 && begin >= end && begin.toLong() + length <= units, "try_bounds")
                    end = begin + length
                    TryRange(begin, end, handlers[u2(at + 6)] ?: throw Rejected("handler_offset"))
                }
            }
            return Code(instructions, registers, ranges, stringCount, typeCount, fieldCount, methodCount)
        }
    }

    companion object {
        private const val MAX_DEX_BYTES = 64 * 1_024 * 1_024
        private val ROOT_DEX = Regex("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")
        private fun validDescriptor(descriptor: String) = descriptor.length in 5..1_024 &&
            descriptor.startsWith('L') && ";->" in descriptor && descriptor.endsWith(")Ljava/lang/Object;")
        private fun demand(value: Boolean, reason: String) { if (!value) throw Rejected(reason) }
        private fun InputStream.boundedBytes(limit: Int): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16_384)
            while (true) { val read = read(buffer); if (read < 0) break
                demand(output.size().toLong() + read <= limit, "dex_size"); output.write(buffer, 0, read) }
            return output.toByteArray()
        }

        /** The test seam consumes bytes, never code loaded by ART or the host JVM. */
        internal fun proveDex(bytes: ByteArray, descriptor: String): Result = try {
            demand(validDescriptor(descriptor), "descriptor")
            val code = Dex(bytes).selected(descriptor)
            if (code == null) Result(false, "method_missing") else analyze(code)
        } catch (failure: Rejected) { Result(false, failure.reason) }
        catch (_: Throwable) { Result(false, "read_or_analysis_failed") }

        private fun analyze(code: Code): Result {
            val insns = decode(code)
            val executable = insns.filterValues { !it.payload }
            demand(executable.containsKey(0), "entry_payload")
            val catches = mutableMapOf<Int, List<Int>>()
            var edges = 0
            for (range in code.tries) {
                demand(range.start in executable && (range.end == code.units.size || range.end in insns), "try_instruction_bounds")
                demand(range.handlers.all { it in executable }, "handler_instruction_bounds")
            }
            for ((address, instruction) in executable) {
                // Only potentially throwing instructions lead to handlers. A fake edge from a NOP
                // could otherwise invent an unreachable null-return witness. Handler state is PRE-write.
                val handlers = if (!instruction.canThrow) emptyList() else
                    code.tries.filter { address >= it.start && address < it.end }.flatMap { it.handlers }.distinct()
                catches[address] = handlers
                edges += instruction.next.size + handlers.size
                demand(edges <= 32_768, "edge_budget")
                // Explicit targets must be valid even in an unreachable block. A padding NOP's
                // fallthrough is checked only if reached, since unreachable payload padding is legal.
                if (instruction.opcode in 0x28..0x2c || instruction.opcode in 0x32..0x3d) {
                    demand(instruction.next.all { it in executable }, "branch_instruction_bounds")
                }
            }
            // UNKNOWN=0, ZERO=1, NONZERO=2. NONZERO only prunes obvious impossible zero tests;
            // it does not encode signs, arithmetic relations, object identities or path feasibility.
            val states = mutableMapOf<Int, ByteArray>()
            val queue = ArrayDeque<Int>()
            val pending = mutableSetOf<Int>()
            fun offer(address: Int, incoming: ByteArray) {
                demand(address in executable, "flow_instruction_bounds")
                val old = states[address]
                val changed = if (old == null) { states[address] = incoming.copyOf(); true } else {
                    var different = false
                    for (i in old.indices) if (old[i] != UNKNOWN && old[i] != incoming[i]) { old[i] = UNKNOWN; different = true }
                    different
                }
                if (changed && pending.add(address)) queue.addLast(address)
            }
            offer(0, ByteArray(code.registers))
            var iterations = 0
            while (queue.isNotEmpty()) {
                demand(++iterations <= 131_072, "flow_budget")
                val address = queue.removeFirst(); pending.remove(address)
                val instruction = executable.getValue(address)
                val before = states.getValue(address)
                catches.getValue(address).forEach { offer(it, before) }
                val after = before.copyOf()
                instruction.dest?.let { destination ->
                    after[destination] = when {
                        instruction.zero -> ZERO
                        instruction.nonzero -> NONZERO
                        instruction.opcode in setOf(0x01, 0x02, 0x03, 0x07, 0x08, 0x09) -> before[checkNotNull(instruction.source)]
                        instruction.opcode == 0x1f -> before[destination] // check-cast does not change null.
                        else -> UNKNOWN
                    }
                    if (instruction.wide) after[destination + 1] = UNKNOWN
                }
                if (instruction.opcode in 0x32..0x3d && instruction.next.size == 2) {
                    val a = instruction.operands[0]
                    val b = instruction.operands.getOrNull(1)
                    val left = after[a]
                    val right = b?.let { after[it] } ?: ZERO
                    val equalKnown = when {
                        a == b || left == ZERO && right == ZERO -> true
                        left == ZERO && right == NONZERO || left == NONZERO && right == ZERO -> false
                        else -> null
                    }
                    val condition = when (instruction.opcode) {
                        0x32, 0x38 -> equalKnown
                        0x33, 0x39 -> equalKnown?.not()
                        0x34, 0x36, 0x3a, 0x3c -> if (a == b || left == ZERO && right == ZERO) false else null
                        0x35, 0x37, 0x3b, 0x3d -> if (a == b || left == ZERO && right == ZERO) true else null
                        else -> null
                    }
                    for ((index, next) in instruction.next.withIndex()) {
                        if (condition != null && condition != (index == 0)) continue
                        val edge = after.copyOf()
                        if (instruction.opcode in setOf(0x32, 0x33, 0x38, 0x39)) {
                            val equalityEdge = (index == 0) == (instruction.opcode == 0x32 || instruction.opcode == 0x38)
                            if (b == null) edge[a] = if (equalityEdge) ZERO else NONZERO
                            else if (equalityEdge) {
                                if (left != UNKNOWN) edge[b] = left
                                if (right != UNKNOWN) edge[a] = right
                            } else {
                                if (left == ZERO) edge[b] = NONZERO
                                if (right == ZERO) edge[a] = NONZERO
                            }
                        }
                        offer(next, edge)
                    }
                } else instruction.next.forEach { offer(it, after) }
            }
            // Evaluate after convergence. A later UNKNOWN join must be able to revoke an early ZERO.
            val proven = executable.values.any { it.opcode == 0x11 && states[it.address]?.get(checkNotNull(it.source)) == ZERO }
            return Result(proven, if (proven) "must_null_return" else "no_proven_null_return", executable.size, edges, code.registers)
        }

        private fun decode(code: Code): Map<Int, Insn> {
            val units = code.units
            fun word(at: Int): Int { demand(at in units.indices, "instruction_bounds"); return units[at] }
            fun signed32(at: Int) = word(at) or (word(at + 1) shl 16)
            fun unsigned32(at: Int) = word(at).toLong() or (word(at + 1).toLong() shl 16)
            fun target(at: Int, offset: Int): Int {
                val value = at.toLong() + offset
                demand(value in 0 until units.size.toLong(), "branch_bounds"); return value.toInt()
            }
            fun register(value: Int): Int { demand(value in 0 until code.registers, "register_bounds"); return value }
            val result = linkedMapOf<Int, Insn>()
            var address = 0
            while (address < units.size) {
                demand(result.size < 4_096, "instruction_budget")
                val encoded = word(address); val opcode = encoded and 255
                if (opcode == 0 && encoded != 0) {
                    demand(address % 2 == 0, "payload_alignment")
                    val width = when (encoded) {
                        0x0100 -> { val count = word(address + 1); demand(count <= 1_024, "switch_budget"); 4 + 2 * count }
                        0x0200 -> { val count = word(address + 1); demand(count <= 1_024, "switch_budget"); 2 + 4 * count }
                        0x0300 -> {
                            val elementWidth = word(address + 1); val count = unsigned32(address + 2)
                            demand(elementWidth in setOf(1, 2, 4, 8) && count <= 16_384, "array_payload_budget")
                            val bytes = elementWidth * count; demand(bytes <= 16_384, "array_payload_budget")
                            4 + ((bytes + 1) / 2).toInt()
                        }
                        else -> throw Rejected("unknown_payload")
                    }
                    demand(address.toLong() + width <= units.size, "payload_bounds")
                    result[address] = Insn(address, opcode, width, null, null, false, false, false, emptyList(), true)
                    address += width; continue
                }
                val format = FORMATS[opcode]
                demand(format != "-", "unsupported_opcode")
                val width = format[0] - '0'
                demand(width in 1..5 && address + width <= units.size, "instruction_bounds")
                val a4 = (encoded ushr 8) and 15; val b4 = (encoded ushr 12) and 15; val a8 = encoded ushr 8
                val registers = when (format) {
                    "10x", "10t", "20t", "30t" -> emptyList()
                    "11n" -> listOf(a4)
                    "12x", "22c", "22s", "22t" -> listOf(a4, b4)
                    "11x", "21c", "21ih", "21lh", "21s", "21t", "31c", "31i", "31t", "51l" -> listOf(a8)
                    "22x" -> listOf(a8, word(address + 1))
                    "32x" -> listOf(word(address + 1), word(address + 2))
                    "23x" -> listOf(a8, word(address + 1) and 255, word(address + 1) ushr 8)
                    "22b" -> listOf(a8, word(address + 1) and 255)
                    "35c" -> {
                        val count = encoded ushr 12; demand(count <= 5, "invoke_register_count")
                        val list = word(address + 2)
                        listOf(list and 15, (list ushr 4) and 15, (list ushr 8) and 15, list ushr 12, a4).take(count)
                    }
                    "3rc" -> {
                        val first = word(address + 2); demand(first.toLong() + a8 <= code.registers, "register_bounds")
                        (first until first + a8).toList()
                    }
                    else -> throw Rejected("unsupported_format")
                }.onEach(::register)
                val flags = FLAGS[opcode]
                val destination = if (flags and 1 == 0) null else registers.firstOrNull() ?: throw Rejected("destination")
                val wide = flags and 2 != 0
                if (wide) demand(checkNotNull(destination) + 1 < code.registers, "wide_register_bounds")
                if (opcode in 0x04..0x06) demand(registers[1] + 1 < code.registers, "wide_register_bounds")
                val reference = if (width >= 2) word(address + 1) else 0
                when (opcode) {
                    0x1a -> demand(reference < code.strings, "instruction_string_index")
                    0x1b -> demand(unsigned32(address + 1) < code.strings, "instruction_string_index")
                    0x1c, 0x1f, 0x20, 0x22, 0x23, 0x24, 0x25 -> demand(reference < code.types, "instruction_type_index")
                    in 0x52..0x6d -> demand(reference < code.fields, "instruction_field_index")
                    in 0x6e..0x72, in 0x74..0x78 -> demand(reference < code.methods, "instruction_method_index")
                }
                demand(opcode !in 0x0e..0x10, "non_object_return")
                val moveSource = when (opcode) {
                    0x01, 0x04, 0x07 -> b4
                    0x02, 0x05, 0x08 -> word(address + 1)
                    0x03, 0x06, 0x09 -> word(address + 2)
                    0x11 -> a8
                    else -> null
                }
                val zero = when (opcode) {
                    0x12 -> (encoded ushr 12) == 0
                    0x13, 0x15 -> word(address + 1) == 0
                    0x14 -> signed32(address + 1) == 0
                    else -> false
                }
                val fallthrough = address + width
                val next = when (opcode) {
                    0x28 -> listOf(target(address, a8.toByte().toInt()))
                    0x29 -> listOf(target(address, word(address + 1).toShort().toInt()))
                    0x2a -> listOf(target(address, signed32(address + 1)))
                    in 0x32..0x3d -> listOf(target(address, word(address + 1).toShort().toInt()), fallthrough).distinct()
                    0x26, 0x2b, 0x2c -> {
                        val payload = target(address, signed32(address + 1))
                        val expected = when (opcode) { 0x26 -> 0x0300; 0x2b -> 0x0100; else -> 0x0200 }
                        demand(payload % 2 == 0 && word(payload) == expected, "payload_reference")
                        if (opcode == 0x26) listOf(fallthrough) else {
                            val count = word(payload + 1); demand(count <= 1_024, "switch_budget")
                            val targetsAt = if (opcode == 0x2b) payload + 4 else payload + 2 + count * 2
                            if (opcode == 0x2c) for (i in 1 until count) {
                                demand(signed32(payload + 2 + (i - 1) * 2) < signed32(payload + 2 + i * 2), "switch_keys")
                            }
                            listOf(fallthrough) + (0 until count).map { target(address, signed32(targetsAt + it * 2)) }
                        }
                    }
                    else -> if (flags and 8 != 0) listOf(fallthrough) else emptyList()
                }
                val nonzero = opcode in setOf(0x0d, 0x1a, 0x1b, 0x1c, 0x22, 0x23) || opcode in 0x12..0x15 && !zero
                // dexlib's historical flags omit fill-array-data's null/bounds exceptions.
                result[address] = Insn(address, opcode, width, destination, moveSource, zero, wide,
                    flags and 4 != 0 || opcode == 0x26, next, operands = registers, nonzero = nonzero)
                address += width
            }
            // A reference must identify a decoded payload start, never a lookalike data word inside code.
            for (instruction in result.values.filter { !it.payload && it.opcode in setOf(0x26, 0x2b, 0x2c) }) {
                val payload = target(instruction.address, signed32(instruction.address + 1))
                demand(result[payload]?.payload == true, "payload_instruction_bounds")
            }
            return result
        }

        // Generated from smali-dexlib2 3.0.10 Opcodes.forDexVersion(39); standard 00..e2 only.
        // FLAGS: 1=setsRegister, 2=setsWideRegister, 4=canThrow, 8=canContinue.
        private val FORMATS = arrayOf(
            "10x", "12x", "22x", "32x", "12x", "22x", "32x", "12x", "22x", "32x", "11x", "11x", "11x", "11x", "10x", "11x",
            "11x", "11x", "11n", "21s", "31i", "21ih", "21s", "31i", "51l", "21lh", "21c", "31c", "21c", "11x", "11x", "21c",
            "22c", "12x", "21c", "22c", "35c", "3rc", "31t", "11x", "10t", "20t", "30t", "31t", "31t", "23x", "23x", "23x",
            "23x", "23x", "22t", "22t", "22t", "22t", "22t", "22t", "21t", "21t", "21t", "21t", "21t", "21t", "-", "-",
            "-", "-", "-", "-", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x",
            "23x", "23x", "22c", "22c", "22c", "22c", "22c", "22c", "22c", "22c", "22c", "22c", "22c", "22c", "22c", "22c",
            "21c", "21c", "21c", "21c", "21c", "21c", "21c", "21c", "21c", "21c", "21c", "21c", "21c", "21c", "35c", "35c",
            "35c", "35c", "35c", "-", "3rc", "3rc", "3rc", "3rc", "3rc", "-", "-", "12x", "12x", "12x", "12x", "12x",
            "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x",
            "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x",
            "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x", "23x",
            "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x",
            "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x", "12x",
            "22s", "22s", "22s", "22s", "22s", "22s", "22s", "22s", "22b", "22b", "22b", "22b", "22b", "22b", "22b", "22b",
            "22b", "22b", "22b", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-",
            "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-", "-",
        )
        private val FLAGS = intArrayOf(
            8, 9, 9, 9, 11, 11, 11, 9, 9, 9, 9, 11, 9, 9, 0, 0,
            0, 0, 9, 9, 9, 9, 11, 11, 11, 11, 13, 13, 13, 12, 12, 13,
            13, 13, 13, 13, 12, 12, 8, 4, 0, 0, 0, 8, 8, 9, 9, 9,
            9, 9, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 0, 0,
            0, 0, 0, 0, 13, 15, 13, 13, 13, 13, 13, 12, 12, 12, 12, 12,
            12, 12, 13, 15, 13, 13, 13, 13, 13, 12, 12, 12, 12, 12, 12, 12,
            13, 15, 13, 13, 13, 13, 13, 12, 12, 12, 12, 12, 12, 12, 12, 12,
            12, 12, 12, 0, 12, 12, 12, 12, 12, 0, 0, 9, 9, 11, 11, 9,
            11, 11, 9, 11, 9, 9, 11, 9, 11, 11, 9, 11, 9, 9, 9, 9,
            9, 9, 9, 13, 13, 9, 9, 9, 9, 9, 9, 11, 11, 11, 15, 15,
            11, 11, 11, 11, 11, 11, 9, 9, 9, 9, 9, 11, 11, 11, 11, 11,
            9, 9, 9, 13, 13, 9, 9, 9, 9, 9, 9, 11, 11, 11, 15, 15,
            11, 11, 11, 11, 11, 11, 9, 9, 9, 9, 9, 11, 11, 11, 11, 11,
            9, 9, 9, 13, 13, 9, 9, 9, 9, 9, 9, 13, 13, 9, 9, 9,
            9, 9, 9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        private const val UNKNOWN: Byte = 0
        private const val ZERO: Byte = 1
        private const val NONZERO: Byte = 2
    }
}
