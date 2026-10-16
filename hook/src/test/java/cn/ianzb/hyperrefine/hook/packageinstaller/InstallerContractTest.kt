package cn.ianzb.hyperrefine.hook.packageinstaller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerContractTest {
    private val installer = PackageInstallerKeys.PACKAGE_NAME

    @Test fun onlyAnalysedSdkAndMainProcessAreEligible() {
        assertTrue(InstallerContract.supports(37, installer, installer, false))
        assertFalse(InstallerContract.supports(36, installer, installer, false))
        assertFalse(InstallerContract.supports(38, installer, installer, false))
        assertFalse(InstallerContract.supports(37, installer, "$installer:other", false))
        assertFalse(InstallerContract.supports(37, "android", installer, false))
        assertFalse(InstallerContract.supports(37, installer, installer, true))
    }

    @Test fun exactReturnAndStaticnessAreRequiredEvenWhenNameMatches() {
        val owner = Fixture::class.java
        val instance = InstallerContract.method(owner, "value", "int", false, "java.lang.String")
        assertEquals(4, instance.invoke(Fixture(), "test"))
        assertThrows(IllegalStateException::class.java) {
            InstallerContract.method(owner, "value", "boolean", false, "java.lang.String")
        }
        assertThrows(IllegalStateException::class.java) {
            InstallerContract.method(owner, "value", "int", true, "java.lang.String")
        }
        assertThrows(IllegalStateException::class.java) {
            InstallerContract.method(owner, "value", "int", false, "java.lang.Integer")
        }
    }

    @Test fun missingMemberCannotFallBackToAnUnrelatedBooleanMethod() {
        assertThrows(IllegalStateException::class.java) {
            InstallerContract.method(Fixture::class.java, "missing", "boolean", false)
        }
    }

    @Test fun failedFeatureRollsBackInReverseOrderAndStopsInstalling() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("mount failed")
        val actual = assertThrows(IllegalStateException::class.java) {
            InstallerContract.installAtomically(listOf(
                { events += "install-a"; { events += "remove-a"; Unit } },
                { events += "install-b"; { events += "remove-b"; Unit } },
                { throw failure },
                { events += "must-not-run"; { Unit } },
            ))
        }
        assertTrue(actual === failure)
        assertEquals(listOf("install-a", "install-b", "remove-b", "remove-a"), events)
    }

    @Test fun successfulFeatureKeepsItsHandles() {
        val events = mutableListOf<String>()
        InstallerContract.installAtomically(listOf(
            { events += "install"; { events += "remove"; Unit } },
        ))
        assertEquals(listOf("install"), events)
    }

    @Test fun rollbackFailureIsVisibleAndDoesNotPreventOtherRemovals() {
        val events = mutableListOf<String>()
        val failure = IllegalStateException("install failed")
        val rollback = IllegalArgumentException("remove failed")
        assertThrows(IllegalStateException::class.java) {
            InstallerContract.installAtomically(listOf(
                { { events += "removed-a"; Unit } },
                { { throw rollback } },
                { throw failure },
            ))
        }
        assertEquals(listOf("removed-a"), events)
        assertEquals(listOf(rollback), failure.suppressed.toList())
    }

    class Fixture {
        fun value(value: String): Int = value.length
        fun unrelated(): Boolean = true
    }
}
