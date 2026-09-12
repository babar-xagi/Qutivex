package dev.qutivex.engine.project

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectLockTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `acquires exclusive lock and releases file on close`() {
        val lock = ProjectLockManager.acquire(tempDir, "install", timeoutMs = 200)
        assertEquals("install", lock.operation)
        assertTrue(lock.pid > 0)
        assertTrue(Files.exists(tempDir.resolve(".qutivex/project.lock")))

        lock.close()
        assertFalse(Files.exists(tempDir.resolve(".qutivex/project.lock")))
    }

    @Test
    fun `concurrent acquire on locked project throws ProjectLockedException with details`() {
        val lock1 = ProjectLockManager.acquire(tempDir, "add", timeoutMs = 200)
        try {
            val ex = assertThrows<ProjectLockedException> {
                ProjectLockManager.acquire(tempDir, "remove", timeoutMs = 150)
            }
            assertEquals("add", ex.operation)
            assertEquals(lock1.pid, ex.pid)
            assertTrue(ex.message!!.contains("Another Qutivex operation is currently modifying this project"))
            assertTrue(ex.message!!.contains("Operation: add"))
        } finally {
            lock1.close()
        }
    }

    @Test
    fun `recovers from stale lock when previous process is dead`() {
        val qutivexDir = tempDir.resolve(".qutivex")
        Files.createDirectories(qutivexDir)
        val lockFile = qutivexDir.resolve("project.lock")

        // Write a stale lock file with an absurdly high PID that is definitely not running
        val stalePid = 999999999L
        Files.writeString(lockFile, "pid=$stalePid\noperation=crashed-op\ntimestamp=1000\n")

        val lock = ProjectLockManager.acquire(tempDir, "install", timeoutMs = 300)
        assertEquals("install", lock.operation)
        assertTrue(lock.pid > 0)
        lock.close()
    }
}
