/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2021 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck.test.verifier.nvm.buffer

import com.intel.pmem.llpl.Heap
import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.annotations.DurableRecoverAll
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.test.verifier.durable.MCAS
import org.jetbrains.kotlinx.lincheck.test.verifier.durable.SequentialMCAS
import org.jetbrains.kotlinx.lincheck.test.verifier.nlr.AbstractNVMLincheckTest
import java.io.File
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.*


private const val THREADS_NUMBER = 3
private const val N = 3 // change MCASTest#compareAndSet parameter list also

private const val PATH = "lol"

@Param(name = "01", gen = IntGen::class, conf = "0:1")
internal class MCASTestByteBuffer :
    AbstractNVMLincheckTest(Recover.DURABLE, THREADS_NUMBER, SequentialMCAS::class, false) {
    private val heap = if (Heap.exists(PATH)) Heap.openHeap(PATH) else Heap.createHeap(PATH)

    private val cas = DurableMCAS()

    @Operation
    fun get(@Param(gen = IntGen::class, conf = "0:${N - 1}") index: Int) = cas[index]

    @Operation
    fun compareAndSet(
        @Param(name = "01") o1: Int,
        @Param(name = "01") o2: Int,
        @Param(name = "01") o3: Int,
        @Param(name = "01") n1: Int,
        @Param(name = "01") n2: Int,
        @Param(name = "01") n3: Int
    ) = cas.compareAndSet(listOf(o1, o2, o3), listOf(n1, n2, n3))

    @DurableRecoverAll
    fun recover() = cas.recover()
}


private const val ACTIVE = (0).toByte()
private const val SUCCESSFUL = (1).toByte()
private const val FAILED = (2).toByte()
private const val SUCCESSFUL_DIRTY = (3).toByte()
private const val FAILED_DIRTY = (4).toByte()
private fun isDirty(status: Byte) = status == SUCCESSFUL_DIRTY || status == FAILED_DIRTY
private fun clean(status: Byte) = when (status) {
    SUCCESSFUL_DIRTY -> SUCCESSFUL
    FAILED_DIRTY -> FAILED
    else -> status
}

internal class WordDescriptor(private val buffer: ByteBuffer, val index: Int) {
    constructor(manager: ByteBufferMemoryManager) : this(manager.buffer, manager.requestRange(12))

    fun getOld() = buffer.getInt(index)
    fun getNew() = buffer.getInt(index + 4)
    fun getParent() = buffer.getInt(index + 8)

    fun write(old: Int, new: Int, parent: Int) {
        buffer.putInt(index, old)
        buffer.putInt(index + 4, new)
        buffer.putInt(index + 8, parent)
    }
}

internal class MCASDescriptor(private val buffer: ByteBuffer, val index: Int) {
    constructor(manager: ByteBufferMemoryManager) : this(manager.buffer, manager.requestRange(13))

    fun getStatus(): Byte = buffer.get(index)
    fun getWord(i: Int): Int = buffer.getInt(index + 1 + i * 4)

    fun write(status: Byte, words: List<Int>) {
        writeStatus(status)
        words.forEachIndexed { i, v ->
            buffer.putInt(index + 1 + 4 * i, v)
        }
    }

    fun writeStatus(status: Byte) {
        synchronized("MCASDescriptor") {
            buffer.put(index, status)
        }

    }

    fun CAS_Status(old: Byte, new: Byte): Boolean {
        synchronized("MCASDescriptor") {
            if (getStatus() == old) {
                writeStatus(new)
                return true
            }
            return false
        }
    }
}

internal class Descriptors(private val buffer: ByteBuffer, val index: Int) {
    constructor(manager: ByteBufferMemoryManager) : this(manager.buffer, manager.requestRange(12))

    fun write(data: List<Int>) {
        data.forEachIndexed { i, v ->
            buffer.putInt(index + i * 4, v)
        }
    }

    fun get(i: Int) = buffer.getInt(index + i * 4)
    fun CAS(i: Int, old: Int, new: Int): Boolean {
        synchronized("Descriptors") {
            if (get(i) == old) {
                buffer.putInt(index + i * 4, new)
                return true
            }
            return false
        }
    }
}

internal open class DurableMCAS : MCAS {
    private val fileChannel = Files.newByteChannel(
        File("test").toPath(),
        EnumSet.of(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
    ) as FileChannel
    private val buffer: MappedByteBuffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, 10_000)
    private val manager: ByteBufferMemoryManager = ByteBufferMemoryManager(buffer)
    private val dataIndex: Int

    init {
        val descriptors = Descriptors(manager)
        dataIndex = descriptors.index
        val mcas = MCASDescriptor(manager)
        val data = List(N) {
            val wd = WordDescriptor(manager)
            wd.write(0, 0, mcas.index)
            wd.index
        }
        mcas.write(SUCCESSFUL, data)
        descriptors.write(data)
    }

    fun close() {
        fileChannel.close()
    }

    protected open fun readInternal(self: MCASDescriptor?, index: Int): Pair<WordDescriptor, Int> {
        val descriptors = Descriptors(buffer, dataIndex)
        while (true) {
            val wd = WordDescriptor(buffer, descriptors.get(index))
            val parent = MCASDescriptor(buffer, wd.getParent())
            val status = parent.getStatus()
            if ((self === null || parent.index != self.index) && status == ACTIVE) {
                MCAS(parent)
                continue
            } else if (isDirty(status)) {
                buffer.force()
                parent.writeStatus(clean(status))
                continue
            }
            return if (status == SUCCESSFUL) wd to wd.getNew() else wd to wd.getOld()
        }
    }

    protected open fun MCAS(self: MCASDescriptor): Boolean {
        val descriptors = Descriptors(buffer, dataIndex)
        var success = true
        loop@ for (index in 0 until N) {
            val wd = WordDescriptor(buffer, self.getWord(index))
            retry@ while (true) {
                val (content, value) = readInternal(self, index)
                if (content.index == wd.index) break@retry
                if (value != wd.getOld()) {
                    success = false
                    break@loop
                }
                if (self.getStatus() != ACTIVE) break@loop
                if (descriptors.CAS(index, content.index, wd.index)) break@retry
            }
        }
        buffer.force()
        self.CAS_Status(ACTIVE, if (success) SUCCESSFUL_DIRTY else FAILED_DIRTY)
        buffer.force()
        self.writeStatus(clean(self.getStatus()))
        return self.getStatus() == SUCCESSFUL
    }

    override fun get(index: Int) = readInternal(null, index).second
    override fun compareAndSet(old: List<Int>, new: List<Int>): Boolean {
        val mcas = MCASDescriptor(manager)
        val words = old.indices.map {
            val wd = WordDescriptor(manager)
            wd.write(old[it], new[it], mcas.index)
            wd.index
        }
        mcas.write(ACTIVE, words)
        return MCAS(mcas)
    }

    override fun recover() {
        val descriptors = Descriptors(buffer, dataIndex)
        for (index in 0 until N) {
            val wd = WordDescriptor(buffer, descriptors.get(index))
            val mcas = MCASDescriptor(buffer, wd.getParent())
            val status = mcas.getStatus()
            if (status == ACTIVE) {
                mcas.writeStatus(FAILED)
            } else {
                mcas.writeStatus(clean(status))
            }
        }
        buffer.force()
    }
}


class ByteBufferMemoryManager(val buffer: ByteBuffer) {
    private val occupied = atomic(0)
    fun requestRange(size: Int): Int {
        val start = occupied.getAndAdd(size)
        check(start + size < buffer.capacity())
        return start
    }
}
