package dev.sebastiano.ciacola.document

import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Outcome of an atomic file exchange (`renamex_np(..., RENAME_SWAP)` on macOS,
 * `renameat2(..., RENAME_EXCHANGE)` on Linux).
 */
sealed interface AtomicExchangeResult {
    data object Success : AtomicExchangeResult
    data class Unsupported(val reason: String) : AtomicExchangeResult
    data class Failed(val errno: Int, val message: String) : AtomicExchangeResult
}

/**
 * Low-level filesystem primitives required by [DeckFileSync]. Abstracted as an interface so
 * tests can inject exchange-unavailable conditions and I/O failures at exact step boundaries while
 * production code uses [NativeAtomicFileOps].
 */
interface AtomicFileOps {
    fun probeExchangeSupport(syncDir: Path): AtomicExchangeResult
    fun exchange(pathA: Path, pathB: Path): AtomicExchangeResult
    fun writeFileAndFsync(path: Path, bytes: ByteArray)
    fun fsyncDirectory(dir: Path)
    fun atomicMove(from: Path, to: Path)
    fun deleteIfExists(path: Path)
}

/**
 * JBR 25 Foreign Function & Memory (FFM) implementation of [AtomicFileOps].
 *
 * Uses `Linker.nativeLinker()` directly with zero JNI/JNA dependencies:
 * - macOS: `renamex_np(from, to, RENAME_SWAP = 0x2)`
 * - Linux: `renameat2(AT_FDCWD = -100, oldpath, AT_FDCWD = -100, newpath, RENAME_EXCHANGE = 2)`
 * - Other platforms: returns [AtomicExchangeResult.Unsupported] so the deck opens read-only and
 *   never overwrites.
 */
object NativeAtomicFileOps : AtomicFileOps {

    private const val MAC_RENAME_SWAP = 0x00000002
    private const val MAC_ENOTSUP = 45
    private const val MAC_EOPNOTSUPP = 102
    private const val MAC_ENOSYS = 78
    private const val MAC_EXDEV = 18

    private const val LINUX_AT_FDCWD = -100
    private const val LINUX_RENAME_EXCHANGE = 2
    private const val LINUX_ENOSYS = 38
    private const val LINUX_EINVAL = 22
    private const val LINUX_EOPNOTSUPP = 95
    private const val LINUX_EXDEV = 18

    private val osNameLower: String = System.getProperty("os.name", "").lowercase()
    private val isMac: Boolean = "mac" in osNameLower || "darwin" in osNameLower
    private val isLinux: Boolean = "linux" in osNameLower

    private val errnoCapture = Linker.Option.captureCallState("errno")
    private val errnoLayout = Linker.Option.captureStateLayout()
    private val errnoOffset = errnoLayout.byteOffset(MemoryLayout.PathElement.groupElement("errno"))

    private val renamexNpHandle: MethodHandle? by lazy {
        if (!isMac) return@lazy null
        runCatching {
            val linker = Linker.nativeLinker()
            val symbol = linker.defaultLookup().find("renamex_np").orElse(null) ?: return@runCatching null
            linker.downcallHandle(
                symbol,
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                ),
                errnoCapture,
            )
        }.getOrNull()
    }

    private val renameat2Handle: MethodHandle? by lazy {
        if (!isLinux) return@lazy null
        runCatching {
            val linker = Linker.nativeLinker()
            val symbol = linker.defaultLookup().find("renameat2").orElse(null) ?: return@runCatching null
            linker.downcallHandle(
                symbol,
                FunctionDescriptor.of(
                    ValueLayout.JAVA_INT,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT,
                ),
                errnoCapture,
            )
        }.getOrNull()
    }

    override fun probeExchangeSupport(syncDir: Path): AtomicExchangeResult {
        if (!isMac && !isLinux) {
            return AtomicExchangeResult.Unsupported(
                "Atomic file exchange is not supported on OS '${System.getProperty("os.name")}'",
            )
        }
        if (isMac && renamexNpHandle == null) {
            return AtomicExchangeResult.Unsupported("macOS renamex_np symbol unavailable")
        }
        if (isLinux && renameat2Handle == null) {
            return AtomicExchangeResult.Unsupported("Linux renameat2 symbol unavailable")
        }

        val probeA = syncDir.resolve(".exchange-probe-a-${System.nanoTime()}")
        val probeB = syncDir.resolve(".exchange-probe-b-${System.nanoTime()}")
        return try {
            Files.write(probeA, byteArrayOf(1))
            Files.write(probeB, byteArrayOf(2))
            exchange(probeA, probeB)
        } catch (e: IOException) {
            AtomicExchangeResult.Unsupported("Atomic exchange probe failed: ${e.message}")
        } finally {
            runCatching { Files.deleteIfExists(probeA) }
            runCatching { Files.deleteIfExists(probeB) }
        }
    }

    override fun exchange(pathA: Path, pathB: Path): AtomicExchangeResult {
        val absA = pathA.toAbsolutePath().normalize().toString()
        val absB = pathB.toAbsolutePath().normalize().toString()

        if (isMac) {
            val handle = renamexNpHandle
                ?: return AtomicExchangeResult.Unsupported("macOS renamex_np symbol unavailable")
            return try {
                Arena.ofConfined().use { arena ->
                    val callState = arena.allocate(errnoLayout)
                    val cA = arena.allocateFrom(absA)
                    val cB = arena.allocateFrom(absB)
                    val rc = handle.invokeExact(callState, cA, cB, MAC_RENAME_SWAP) as Int
                    if (rc == 0) {
                        AtomicExchangeResult.Success
                    } else {
                        val errno = callState.get(ValueLayout.JAVA_INT, errnoOffset)
                        when (errno) {
                            MAC_ENOTSUP, MAC_EOPNOTSUPP, MAC_ENOSYS, MAC_EXDEV ->
                                AtomicExchangeResult.Unsupported("renamex_np(RENAME_SWAP) unsupported on filesystem (errno=$errno)")
                            else ->
                                AtomicExchangeResult.Failed(errno, "renamex_np(RENAME_SWAP) failed with errno=$errno")
                        }
                    }
                }
            } catch (e: Exception) {
                AtomicExchangeResult.Failed(-1, "renamex_np(RENAME_SWAP) invocation failed: ${e.message}")
            }
        }

        if (isLinux) {
            val handle = renameat2Handle
                ?: return AtomicExchangeResult.Unsupported("Linux renameat2 symbol unavailable")
            return try {
                Arena.ofConfined().use { arena ->
                    val callState = arena.allocate(errnoLayout)
                    val cA = arena.allocateFrom(absA)
                    val cB = arena.allocateFrom(absB)
                    val rc = handle.invokeExact(callState, LINUX_AT_FDCWD, cA, LINUX_AT_FDCWD, cB, LINUX_RENAME_EXCHANGE) as Int
                    if (rc == 0) {
                        AtomicExchangeResult.Success
                    } else {
                        val errno = callState.get(ValueLayout.JAVA_INT, errnoOffset)
                        when (errno) {
                            LINUX_ENOSYS, LINUX_EINVAL, LINUX_EOPNOTSUPP, LINUX_EXDEV ->
                                AtomicExchangeResult.Unsupported("renameat2(RENAME_EXCHANGE) unsupported on filesystem (errno=$errno)")
                            else ->
                                AtomicExchangeResult.Failed(errno, "renameat2(RENAME_EXCHANGE) failed with errno=$errno")
                        }
                    }
                }
            } catch (e: Exception) {
                AtomicExchangeResult.Failed(-1, "renameat2(RENAME_EXCHANGE) invocation failed: ${e.message}")
            }
        }

        return AtomicExchangeResult.Unsupported(
            "Atomic file exchange is not supported on OS '${System.getProperty("os.name")}'",
        )
    }

    override fun writeFileAndFsync(path: Path, bytes: ByteArray) {
        FileChannel.open(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val buf = ByteBuffer.wrap(bytes)
            while (buf.hasRemaining()) {
                channel.write(buf)
            }
            channel.force(true)
        }
    }

    override fun fsyncDirectory(dir: Path) {
        if (!Files.isDirectory(dir)) return
        FileChannel.open(dir, StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }

    override fun atomicMove(from: Path, to: Path) {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }
}
