package dev.qutivex.core.dependency

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ComparableVersionTest {

    @Test
    fun `numeric versions compare properly`() {
        assertTrue(ComparableVersion("1.10.2") > ComparableVersion("1.9.0"))
        assertTrue(ComparableVersion("5.12.2") > ComparableVersion("5.10.2"))
        assertTrue(ComparableVersion("2.1.0") > ComparableVersion("2.0.21"))
        assertEquals(0, ComparableVersion("1.0.0").compareTo(ComparableVersion("1.0.0")))
        assertEquals(0, ComparableVersion("1.0").compareTo(ComparableVersion("1.0.0")))
    }

    @Test
    fun `qualifier versions compare properly`() {
        assertTrue(ComparableVersion("1.0.0") > ComparableVersion("1.0.0-alpha"))
        assertTrue(ComparableVersion("1.0.0-beta") > ComparableVersion("1.0.0-alpha"))
        assertTrue(ComparableVersion("1.0.0-rc1") > ComparableVersion("1.0.0-beta2"))
        assertTrue(ComparableVersion("1.0.0") > ComparableVersion("1.0.0-rc1"))
        assertTrue(ComparableVersion("1.0.0-sp1") > ComparableVersion("1.0.0"))
    }

    @Test
    fun `sorting versions finds highest version`() {
        val versions = listOf(
            ComparableVersion("1.9.0"),
            ComparableVersion("1.10.2"),
            ComparableVersion("1.8.0"),
            ComparableVersion("1.10.1"),
        )
        val highest = versions.maxOrNull()
        assertEquals("1.10.2", highest?.value)
    }
}
