package dev.qutivex.engine.project

import dev.qutivex.engine.dependency.DependencyException
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

class ProjectLockedException(
    val pid: Long,
    val operation: String,
    message: String = buildString {
        append("Another Qutivex operation is currently modifying this project.\n\n")
        append("Process: $pid\n")
        append("Operation: $operation\n\n")
        append("Wait for it to finish and retry.")
    }
) : DependencyException(message)

/**
 * Ensures mutual exclusion for mutating operations within a Qutivex project directory.
 */
interface ProjectLock : Closeable {
    val operation: String
    val pid: Long
}

object ProjectLockManager {

    private data class LockInfo(val pid: Long, val operation: String, val timestamp: Long)
    private val inProcessLocks = ConcurrentHashMap<Path, LockInfo>()

    fun acquire(
        projectDir: Path,
        operation: String,
        timeoutMs: Long = 500,
    ): ProjectLock {
        val normalizedDir = projectDir.toAbsolutePath().normalize()
        val qutivexDir = normalizedDir.resolve(".qutivex")
        Files.createDirectories(qutivexDir)
        val lockFile = qutivexDir.resolve("project.lock")
        val infoFile = qutivexDir.resolve("project.lock.info")

        val currentPid = ProcessHandle.current().pid()
        val startTime = System.currentTimeMillis()

        while (true) {
            val channel = try {
                FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.READ,
                    StandardOpenOption.WRITE,
                )
            } catch (_: Exception) {
                null
            }

            var fileLock: FileLock? = null
            if (channel != null) {
                try {
                    fileLock = channel.tryLock()
                } catch (_: Exception) {
                    // Lock acquisition failed due to overlapping lock in another thread/process
                }
            }

            if (channel != null && fileLock != null) {
                // Successfully acquired OS-level lock
                try {
                    channel.truncate(0)
                    val info = "pid=$currentPid\noperation=$operation\ntimestamp=${System.currentTimeMillis()}\n"
                    channel.write(ByteBuffer.wrap(info.toByteArray(UTF_8)))
                    channel.force(true)
                } catch (_: Exception) {}

                try {
                    val infoContent = "pid=$currentPid\noperation=$operation\ntimestamp=${System.currentTimeMillis()}\n"
                    Files.writeString(infoFile, infoContent)
                } catch (_: Exception) {}

                inProcessLocks[normalizedDir] = LockInfo(currentPid, operation, System.currentTimeMillis())

                return FileProjectLock(channel, fileLock, lockFile, infoFile, normalizedDir, currentPid, operation)
            } else {
                try {
                    channel?.close()
                } catch (_: Exception) {}

                // Inspect existing lock holder (in-process cache or disk)
                val inProcess = inProcessLocks[normalizedDir]
                val diskHolder = if (inProcess == null) readLockInfo(qutivexDir) else null
                val holderPid = inProcess?.pid ?: diskHolder?.first ?: -1L
                val holderOp = inProcess?.operation ?: diskHolder?.second ?: "mutation"

                if (holderPid > 0) {
                    val isHolderAlive = try {
                        ProcessHandle.of(holderPid).map { it.isAlive }.orElse(false)
                    } catch (_: Exception) {
                        false
                    }

                    if (!isHolderAlive) {
                        // Stale lock from crashed process, try to clean it up
                        try {
                            Files.deleteIfExists(infoFile)
                            Files.deleteIfExists(lockFile)
                        } catch (_: Exception) {}
                    }
                }

                if (System.currentTimeMillis() - startTime >= timeoutMs) {
                    throw ProjectLockedException(
                        pid = if (holderPid > 0) holderPid else currentPid,
                        operation = holderOp,
                    )
                }

                Thread.sleep(50)
            }
        }
    }

    private fun readLockInfo(qutivexDir: Path): Pair<Long, String>? {
        val infoFile = qutivexDir.resolve("project.lock.info")
        val lockFile = qutivexDir.resolve("project.lock")
        val fileToRead = if (Files.exists(infoFile)) infoFile else if (Files.exists(lockFile)) lockFile else return null
        return try {
            val content = Files.readString(fileToRead)
            var pid: Long? = null
            var op: String? = null
            if (content.contains("{") && content.contains("}")) {
                val pidMatch = Regex("\"pid\"\\s*:\\s*(\\d+)").find(content)
                val opMatch = Regex("\"operation\"\\s*:\\s*\"([^\"]+)\"").find(content)
                pid = pidMatch?.groupValues?.get(1)?.toLongOrNull()
                op = opMatch?.groupValues?.get(1)
            } else {
                val props = Properties()
                content.byteInputStream().use { props.load(it) }
                pid = props.getProperty("pid")?.toLongOrNull()
                op = props.getProperty("operation")
            }
            if (pid != null) Pair(pid, op ?: "mutation") else null
        } catch (_: Exception) {
            null
        }
    }

    private class FileProjectLock(
        private val channel: FileChannel,
        private val fileLock: FileLock,
        private val lockFile: Path,
        private val infoFile: Path,
        private val projectDir: Path,
        override val pid: Long,
        override val operation: String,
    ) : ProjectLock {
        private var released = false

        override fun close() {
            if (released) return
            released = true
            inProcessLocks.remove(projectDir)
            try {
                if (fileLock.isValid) {
                    fileLock.release()
                }
            } catch (_: Exception) {}
            try {
                channel.close()
            } catch (_: Exception) {}
            try {
                Files.deleteIfExists(infoFile)
            } catch (_: Exception) {}
            try {
                Files.deleteIfExists(lockFile)
            } catch (_: Exception) {}
        }
    }
}
