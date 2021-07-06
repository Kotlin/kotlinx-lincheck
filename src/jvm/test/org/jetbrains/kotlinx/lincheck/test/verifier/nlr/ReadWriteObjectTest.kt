/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.test.verifier.nlr

import org.jetbrains.kotlinx.lincheck.Actor
import org.jetbrains.kotlinx.lincheck.CTestConfiguration
import org.jetbrains.kotlinx.lincheck.CTestStructure
import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.OperationIdGen
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import java.lang.reflect.Method
import kotlin.reflect.jvm.javaMethod

private const val THREADS_NUMBER = 3

interface RWO<T> {
    fun read(): T?
    fun write(value: T, p: Int)
}

internal class ReadWriteObjectTest :
    AbstractNVMLincheckTest(Recover.NRL, THREADS_NUMBER, SequentialReadWriteObject::class) {
    private val rwo = NRLReadWriteObject<Pair<Int, Int>>(THREADS_NUMBER + 2)

    @Operation
    fun read() = rwo.read()?.first

    @Operation
    fun write(
        @Param(gen = ThreadIdGen::class) threadId: Int,
        value: Int,
        @Param(gen = OperationIdGen::class) operationId: Int
    ) = rwo.write(value to operationId, threadId)
}

private val nullObject = Any()

internal open class SequentialReadWriteObject : VerifierState() {
    private var value: Int? = null

    fun read() = value
    fun write(newValue: Int) {
        value = newValue
    }

    fun write(ignore: Int, newValue: Int, ignore2: Int) = write(newValue)
    override fun extractState() = value ?: nullObject
}

/**
 * Values must be unique.
 * Use (value, op) with unique op to emulate this.
 * @see  <a href="https://www.cs.bgu.ac.il/~hendlerd/papers/NRL.pdf">Nesting-Safe Recoverable Linearizability</a>
 */
internal open class NRLReadWriteObject<T>(threadsCount: Int, initial: T? = null) : RWO<T> {
    protected val register = nonVolatile(initial)

    // (state, value) for every thread
    protected val state = MutableList(threadsCount) { nonVolatile(0 to null as T?) }

    @Recoverable
    override fun read(): T? = register.value

    @Recoverable(recoverMethod = "writeRecover")
    override fun write(value: T, p: Int) = writeImpl(value, p)

    protected open fun writeImpl(value: T, p: Int) {
        val tmp = register.value
        state[p].value = 1 to tmp
        state[p].flush()
//        register.value = value 
//        register.flush()
        register.setAndFlush(value) // TODO is it possible to replace with code above?
        state[p].value = 0 to value
        state[p].flush()
    }

    protected open fun writeRecover(value: T, p: Int) {
        val (flag, current) = state[p].value
        if (flag == 0 && current != value) return writeImpl(value, p)
        else if (flag == 1 && current === register.value) return writeImpl(value, p)
        state[p].value = 0 to value
        state[p].flush()
    }
}

internal abstract class ReadWriteObjectFailingTest :
    AbstractNVMLincheckFailingTest(Recover.NRL, THREADS_NUMBER, SequentialReadWriteObject::class) {
    protected abstract val rwo: RWO<Pair<Int, Int>>

    @Operation
    fun read() = rwo.read()?.first

    @Operation
    fun write(
        @Param(gen = ThreadIdGen::class) threadId: Int,
        value: Int,
        @Param(gen = OperationIdGen::class) operationId: Int
    ) = rwo.write(value to operationId, threadId)
}

internal class SmallScenarioTest : ReadWriteObjectFailingTest() {
    override val rwo = NRLFailingReadWriteObject1<Pair<Int, Int>>(THREADS_NUMBER + 2)
    override fun <O : Options<O, *>> O.customize() {
        executionGenerator(FailingScenarioGenerator::class.java)
    }
}

internal class FailingScenarioGenerator(testCfg: CTestConfiguration, testStructure: CTestStructure) :
    ExecutionGenerator(testCfg, testStructure) {
    override fun nextExecution(): ExecutionScenario {
        val read = SmallScenarioTest::read.javaMethod
        val write = SmallScenarioTest::write.javaMethod
        return ExecutionScenario(
            emptyList(),
            listOf(
                listOf(actor(read), actor(write, 1, 2, 0)),
                listOf(actor(write, 2, 1, 1))
            ),
            listOf(actor(read))
        )
    }
}

private fun actor(method: Method?, vararg a: Any?) = Actor(method!!, a.toMutableList())

internal class ReadWriteObjectFailingTest1 : ReadWriteObjectFailingTest() {
    override val rwo = NRLFailingReadWriteObject1<Pair<Int, Int>>(THREADS_NUMBER + 2)
}

internal class ReadWriteObjectFailingTest2 : ReadWriteObjectFailingTest() {
    override val rwo = NRLFailingReadWriteObject2<Pair<Int, Int>>(THREADS_NUMBER + 2)
}

internal class ReadWriteObjectFailingTest3 : ReadWriteObjectFailingTest() {
    override val rwo = NRLFailingReadWriteObject3<Pair<Int, Int>>(THREADS_NUMBER + 2)
}

internal class ReadWriteObjectFailingTest4 : ReadWriteObjectFailingTest() {
    override val rwo = NRLFailingReadWriteObject4<Pair<Int, Int>>(THREADS_NUMBER + 2)
}

internal class ReadWriteObjectFailingTest5 : ReadWriteObjectFailingTest() {
    override val rwo = NRLFailingReadWriteObject5<Pair<Int, Int>>(THREADS_NUMBER + 2)
}

internal class NRLFailingReadWriteObject1<T>(threadsCount: Int) : NRLReadWriteObject<T>(threadsCount) {
    override fun writeImpl(value: T, p: Int) {
        val tmp = register.value
        state[p].value = 1 to tmp
        // Otherwise the error is that a thread completes write operation twice.
        // here should be state[p].flush()
        register.setAndFlush(value)
        state[p].value = 0 to value
        state[p].flush()
    }
}

internal class NRLFailingReadWriteObject2<T>(threadsCount: Int) : NRLReadWriteObject<T>(threadsCount) {
    override fun writeImpl(value: T, p: Int) {
        val tmp = register.value
        state[p].value = 1 to tmp
        state[p].flush()
        register.setAndFlush(value)
        state[p].value = 0 to value
        // here should be state[p].flush()
    }
}

internal class NRLFailingReadWriteObject3<T>(threadsCount: Int) : NRLReadWriteObject<T>(threadsCount) {
    override fun writeRecover(value: T, p: Int) {
        val (flag, current) = state[p].value
        if (flag == 0 && current != value) return writeImpl(value, p)
        else if (flag == 1 && current === register.value) return writeImpl(value, p)
        state[p].value = 0 to value
        // here should be state[p].flush()
    }
}

internal class NRLFailingReadWriteObject4<T>(threadsCount: Int) : NRLReadWriteObject<T>(threadsCount) {
    override fun writeRecover(value: T, p: Int) {
        val (flag, current) = state[p].value
        if (flag == 0/* here should be && current != value */) return writeImpl(value, p)
        else if (flag == 1 && current === register.value) return writeImpl(value, p)
        state[p].value = 0 to value
        state[p].flush()
    }
}

internal class NRLFailingReadWriteObject5<T>(threadsCount: Int) : NRLReadWriteObject<T>(threadsCount) {
    override fun writeRecover(value: T, p: Int) {
        val (flag, current) = state[p].value
        if (flag == 0 && current != value) return writeImpl(value, p)
        else if (flag == 1 /* here should be && current === register.value */) return writeImpl(value, p)
        state[p].value = 0 to value
        state[p].flush()
    }
}
