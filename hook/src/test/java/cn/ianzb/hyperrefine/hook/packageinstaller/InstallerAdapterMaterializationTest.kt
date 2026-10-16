package cn.ianzb.hyperrefine.hook.packageinstaller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.FieldData
import org.luckypray.dexkit.result.FieldUsingType
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.result.MethodDataList
import org.luckypray.dexkit.result.UsingFieldData
import java.lang.reflect.InvocationTargetException

/** Runs the production converter with actual DexKit result types. Lazy values are fixtures, not JNI results. */
class InstallerAdapterMaterializationTest {
    @Test fun emptyReferenceCannotHideLaterStaticDefinition() {
        val f = Fixture()
        val empty = f.convert(f.method(0))
        assertFalse(empty.isStatic)
        val full = f.method(1, modifiers = 8, code = listOf(0x12, 0x11))
        val complete = f.convert(full)
        assertTrue(complete.isStatic)
        assertEquals(listOf(0x12, 0x11), complete.opcodes)
        assertSame(complete, f.convert(f.method(0)))
    }

    @Test fun definitionFirstCannotBeDowngradedByReference() {
        val f = Fixture()
        val definition = f.convert(f.method(1, code = listOf(0x12, 0x11)))
        assertSame(definition, f.convert(f.method(0)))
    }

    @Test fun conflictingCompleteSnapshotsFailInsteadOfMerging() {
        val f = Fixture()
        f.convert(f.method(1, code = listOf(0x12, 0x11)))
        val error = assertThrows(InstallerMatchException::class.java) {
            f.convert(f.method(2, code = listOf(0x54, 0x11)))
        }
        assertEquals("dex_index.metadata_conflict", error.role)
        assertEquals(2, error.candidateCount)
    }

    @Test fun repeatedRelationsAreExistenceSetsButOpcodeOrderIsRetained() {
        val f = Fixture()
        val value = f.method(1, code = listOf(0x54, 0x54, 0x11))
        val child = f.method(0, descriptor = "Lfixture/Api;->read()Ljava/lang/String;", prime = false)
        f.lazy(value, "invokes", MethodDataList(listOf(child, child)))
        val field = f.field("Lfixture/Owner;->title:Ljava/lang/String;")
        f.lazy(value, "usingFields", listOf(UsingFieldData(field, FieldUsingType.Read), UsingFieldData(field, FieldUsingType.Read),
            UsingFieldData(field, FieldUsingType.Write), UsingFieldData(field, FieldUsingType.Write)))
        val dto = f.convert(value)
        assertEquals(listOf(child.descriptor), dto.invokes)
        assertEquals(listOf(field.descriptor), dto.readFields)
        assertEquals(listOf(field.descriptor), dto.writtenFields)
        assertEquals(listOf(0x54, 0x54, 0x11), dto.opcodes)
        // The child's uninitialized lazy metadata would call JNI if conversion recursively visited it.
    }

    @Test fun duplicateOnlyChangesDoNotCreateConflicts() {
        val f = Fixture()
        val child = f.method(0, descriptor = "Lfixture/Api;->read()Ljava/lang/String;", prime = false)
        val first = f.method(1, code = listOf(0x6e, 0x11))
        f.lazy(first, "invokes", MethodDataList(listOf(child, child)))
        val second = f.method(2, code = listOf(0x6e, 0x11))
        f.lazy(second, "invokes", MethodDataList(listOf(child)))
        assertSame(f.convert(first), f.convert(second))
    }

    @Test fun declaredAbstractAndNativeSnapshotsAreNotUnknownReferences() {
        for (flag in listOf(0x400, 0x100)) {
            val f = Fixture()
            val declaration = f.convert(f.method(1, modifiers = flag or 8))
            assertTrue(declaration.isStatic)
            assertSame(declaration, f.convert(f.method(0)))
        }
    }

    @Test fun emptySnapshotsCanRefreshWithoutMakingUnknownFlagsAuthoritative() {
        val cache = InstallerMethodSnapshots()
        val first = cache.remember(method(), declaredWithoutCode = false)
        assertEquals(null, cache.complete(first.descriptor))
        val second = cache.remember(method().copy(isStatic = true), declaredWithoutCode = false)
        assertTrue(second.isStatic)
        assertEquals(null, cache.complete(second.descriptor))
        val complete = cache.remember(method().copy(isStatic = true, annotationStrings = setOf("/info/layout/v2")), false)
        assertSame(complete, cache.complete(complete.descriptor))
        cache.clear()
        assertEquals(null, cache.complete(complete.descriptor))
    }

    private fun method() = InstallerMethod("Lfixture/Owner;->body()Ljava/lang/Object;", "fixture.Owner", "body", "java.lang.Object", emptyList(), false)

    private class Fixture {
        private val bridge: DexKitBridge
        private val index: InstallerDexKitIndex
        init {
            // Allocate an inert Java object: no native constructor, open(), handle or target code is run.
            val type = Class.forName("sun.misc.Unsafe")
            val field = type.getDeclaredField("theUnsafe").apply { isAccessible = true }
            bridge = type.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), DexKitBridge::class.java) as DexKitBridge
            index = InstallerDexKitIndex::class.java.getDeclaredConstructor(DexKitBridge::class.java).apply { isAccessible = true }.newInstance(bridge)
        }

        fun method(dex: Int, modifiers: Int = 0, code: List<Int> = emptyList(),
                   descriptor: String = "Lfixture/Owner;->body()Ljava/lang/Object;", prime: Boolean = true): MethodData {
            val result = MethodData::class.java.getDeclaredConstructor(DexKitBridge::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
                Int::class.javaPrimitiveType, List::class.java).apply { isAccessible = true }
                .newInstance(bridge, 9, dex, 2, modifiers, descriptor, 3, emptyList<Int>())
            if (prime) {
                lazy(result, "usingStrings", emptyList<String>())
                lazy(result, "annotations", emptyList<Any>())
                lazy(result, "invokes", MethodDataList())
                lazy(result, "usingFields", emptyList<UsingFieldData>())
                lazy(result, "opCodes", code)
            }
            return result
        }

        fun field(descriptor: String): FieldData = FieldData::class.java.getDeclaredConstructor(DexKitBridge::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }.newInstance(bridge, 4, 0, 2, 0, descriptor, 3)

        fun lazy(value: Any, name: String, content: Any) {
            value.javaClass.getDeclaredField("${name}\$delegate").apply { isAccessible = true }.set(value, lazyOf(content))
        }

        fun convert(data: MethodData): InstallerMethod = try {
            InstallerDexKitIndex::class.java.getDeclaredMethod("materialize", MethodData::class.java).apply { isAccessible = true }
                .invoke(index, data) as InstallerMethod
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }
}
