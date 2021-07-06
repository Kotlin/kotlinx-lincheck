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

package org.jetbrains.kotlinx.lincheck.test.verifier.durable.detectable

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.paramgen.OperationIdGen
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.test.verifier.nlr.AbstractNVMLincheckFailingTest
import org.jetbrains.kotlinx.lincheck.test.verifier.nlr.AbstractNVMLincheckTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState

private const val THREADS_NUMBER = 3

private data class Record(val value: Int, val threadId: Int, val operationId: Int)

@Param(name = "key", gen = IntGen::class, conf = "0:1")
internal class RecoverableRegisterTest : AbstractNVMLincheckTest(
    Recover.DETECTABLE_EXECUTION,
    THREADS_NUMBER,
    SequentialCAS::class
) {
    private val register = RecoverableRegister(THREADS_NUMBER + 2)

    @Operation
    fun cas(
        @Param(name = "key") key: Int,
        @Param(gen = ThreadIdGen::class) threadId: Int,
        @Param(gen = OperationIdGen::class) operationId: Int
    ) = register.cas(key, threadId, operationId)

    @Operation
    fun get() = register.get()
}

internal interface CAS {
    fun get(): Int
    fun cas(key: Int, threadId: Int, operationId: Int): Boolean
}

internal class SequentialCAS : VerifierState(), CAS {
    private var data = 0
    override fun extractState() = data
    override fun get() = data
    override fun cas(key: Int, threadId: Int, operationId: Int) = (data == key).also {
        if (it) data = 1 - key
    }
}

internal class RecoverableRegister(threadsNumber: Int) : CAS {
    // This algorithm supposes atomic write into persistent memory. This works the same as usual write.
    private val register = atomic(Record(0, -1, -1))
    private val r = Array(threadsNumber) { Array(threadsNumber) { nonVolatile(Pair(-1, -1)) } }

    override fun cas(key: Int, threadId: Int, operationId: Int): Boolean {
        val oldValue = key
        val newValue = 1 - key
        // recover
        if (register.value == Record(newValue, threadId, operationId))
            return true
        val p = Pair(newValue, operationId)
        for (a in r[threadId])
            if (a.value == p)
                return true
        // cas logic
        val record = register.value
        if (record.value != oldValue)
            return false
        if (record.threadId != -1) {
            r[record.threadId][threadId].value = record.value to record.operationId
            r[record.threadId][threadId].flush()
        }
        return register.compareAndSet(record, Record(newValue, threadId, operationId))
    }

    override fun get() = register.value.value
}

@Param(name = "key", gen = IntGen::class, conf = "0:1")
internal abstract class RecoverableRegisterFailingTest :
    AbstractNVMLincheckFailingTest(Recover.DETECTABLE_EXECUTION, THREADS_NUMBER, SequentialCAS::class) {
    internal abstract val register: CAS

    @Operation
    fun cas(
        @Param(name = "key") key: Int,
        @Param(gen = ThreadIdGen::class) threadId: Int,
        @Param(gen = OperationIdGen::class) operationId: Int
    ) = register.cas(key, threadId, operationId)

    @Operation
    fun get() = register.get()
}

internal class RecoverableRegisterFailingTest1 : RecoverableRegisterFailingTest() {
    override val register = RecoverableFailingRegister1(THREADS_NUMBER + 2)
}

internal class RecoverableRegisterFailingTest2 : RecoverableRegisterFailingTest() {
    override val register = RecoverableFailingRegister2(THREADS_NUMBER + 2)
}

internal class RecoverableRegisterFailingTest3 : RecoverableRegisterFailingTest() {
    override val register = RecoverableFailingRegister3(THREADS_NUMBER + 2)
}

internal class RecoverableRegisterFailingTest4 : RecoverableRegisterFailingTest() {
    override val register = RecoverableFailingRegister4(THREADS_NUMBER + 2)
}

internal class RecoverableFailingRegister1(threadsNumber: Int) : CAS {
    private val register = atomic(Record(0, -1, -1))
    private val r = Array(threadsNumber) { Array(threadsNumber) { nonVolatile(Pair(-1, -1)) } }

    override fun cas(key: Int, threadId: Int, operationId: Int): Boolean {
        val oldValue = key
        val newValue = 1 - key
        // here should be
//        if (register.value == Record(newValue, threadId, operationId))
//            return true
        val p = Pair(newValue, operationId)
        for (a in r[threadId])
            if (a.value == p)
                return true
        val record = register.value
        if (record.value != oldValue)
            return false
        if (record.threadId != -1) {
            r[record.threadId][threadId].value = record.value to record.operationId
            r[record.threadId][threadId].flush()
        }
        return register.compareAndSet(record, Record(newValue, threadId, operationId))
    }

    override fun get() = register.value.value
}

internal class RecoverableFailingRegister2(threadsNumber: Int) : CAS {
    private val register = atomic(Record(0, -1, -1))
    private val r = Array(threadsNumber) { Array(threadsNumber) { nonVolatile(Pair(-1, -1)) } }

    override fun cas(key: Int, threadId: Int, operationId: Int): Boolean {
        val oldValue = key
        val newValue = 1 - key
        if (register.value == Record(newValue, threadId, operationId))
            return true
        val p = Pair(newValue, operationId)
        // here should be
//        for (a in r[threadId])
//            if (a.value == p)
//                return true
        val record = register.value
        if (record.value != oldValue)
            return false
        if (record.threadId != -1) {
            r[record.threadId][threadId].value = record.value to record.operationId
            r[record.threadId][threadId].flush()
        }
        return register.compareAndSet(record, Record(newValue, threadId, operationId))
    }

    override fun get() = register.value.value
}

internal class RecoverableFailingRegister3(threadsNumber: Int) : CAS {
    private val register = atomic(Record(0, -1, -1))
    private val r = Array(threadsNumber) { Array(threadsNumber) { nonVolatile(Pair(-1, -1)) } }

    override fun cas(key: Int, threadId: Int, operationId: Int): Boolean {
        val oldValue = key
        val newValue = 1 - key
        if (register.value == Record(newValue, threadId, operationId))
            return true
        val p = Pair(newValue, operationId)
        for (a in r[threadId])
            if (a.value == p)
                return true
        val record = register.value
        if (record.value != oldValue)
            return false
        // here should be
//        if (record.threadId != -1) {
//            r[record.threadId][threadId].value = record.value to record.operationId
//            r[record.threadId][threadId].flush()
//        }
        return register.compareAndSet(record, Record(newValue, threadId, operationId))
    }

    override fun get() = register.value.value
}

internal class RecoverableFailingRegister4(threadsNumber: Int) : CAS {
    private val register = nonVolatile(Record(0, -1, -1))
    private val r = Array(threadsNumber) { Array(threadsNumber) { nonVolatile(Pair(-1, -1)) } }

    override fun cas(key: Int, threadId: Int, operationId: Int): Boolean {
        val oldValue = key
        val newValue = 1 - key
        // recover
        if (register.value == Record(newValue, threadId, operationId))
            return true
        val p = Pair(newValue, operationId)
        for (a in r[threadId])
            if (a.value == p)
                return true
        // cas logic
        val record = register.value
        if (record.value != oldValue)
            return false
        if (record.threadId != -1) {
            r[record.threadId][threadId].value = record.value to record.operationId
            r[record.threadId][threadId].flush()
        }
        // Here CAS and flush are not atomic.
        val result = register.compareAndSet(record, Record(newValue, threadId, operationId))
        register.flush()
        return result
    }

    override fun get() = register.value.value
}
