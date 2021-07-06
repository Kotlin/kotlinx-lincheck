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

import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.annotations.Recoverable
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.NonVolatileRef
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import java.util.concurrent.atomic.AtomicMarkableReference
import java.util.concurrent.atomic.AtomicReference


private const val THREADS_NUMBER = 3

internal interface RecoverableSet<T> {
    fun add(p: Int, value: T): Boolean
    fun remove(p: Int, value: T): Boolean
    operator fun contains(value: T): Boolean
}

@Param(name = "key", gen = IntGen::class, conf = "0:3")
internal class SetTest : AbstractNVMLincheckTest(Recover.NRL, THREADS_NUMBER, SequentialSet::class) {
    private val set = NRLSet<Int>(2 + THREADS_NUMBER)

    @Operation
    fun add(@Param(gen = ThreadIdGen::class) threadId: Int, @Param(name = "key") key: Int) = set.add(threadId, key)

    @Operation
    fun remove(@Param(gen = ThreadIdGen::class) threadId: Int, @Param(name = "key") key: Int) =
        set.remove(threadId, key)

    @Operation
    fun contains(@Param(name = "key") key: Int) = set.contains(key)
}

internal class SequentialSet : VerifierState() {
    private val set = mutableSetOf<Int>()

    fun add(ignore: Int, key: Int) = set.add(key)
    fun remove(ignore: Int, key: Int) = set.remove(key)
    fun contains(key: Int) = set.contains(key)

    override fun extractState() = set.toList()
}

private const val NULL_DELETER = -1

/**
 * @see [Tracking in Order to Recover: Recoverable Lock-Free Data Structures](https://arxiv.org/pdf/1905.13600.pdf)
 */
internal open class NRLSet<T : Comparable<T>>(threadsCount: Int) : RecoverableSet<T> {

    protected inner class Node(val value: T, next: Node?) {
        val next = AtomicMarkableReference(next, false) // TODO use nonVolatile here
        val deleter = nonVolatile(NULL_DELETER)
    }

    protected inner class Info(val node: NonVolatileRef<Node?> = nonVolatile(null)) {
        val result = nonVolatile(null as Boolean?)
    }

    protected inner class PrevNextPair(val previous: Node?, val next: Node?)

    protected val recoveryData = MutableList(threadsCount) { nonVolatile<Info?>(null) }
    protected val checkPointer = Array(threadsCount) { nonVolatile(0) }
    protected val head = AtomicReference<Node?>(null)

    @Recoverable
    protected open fun findPrevNext(value: T): PrevNextPair {
        start@ while (true) {
            var previous: Node? = null
            var current = head.get()
            while (current != null) {
                val isDeleted = booleanArrayOf(false)
                val next = current.next[isDeleted]
                if (isDeleted[0]) {
                    if (previous?.next?.compareAndSet(current, next, false, false)
                            ?: head.compareAndSet(current, next)
                    ) {
                        current = next
                        continue
                    } else {
                        continue@start
                    }
                }
                if (current.value >= value) {
                    break
                }
                previous = current
                current = next
            }
            return PrevNextPair(previous, current)
        }
    }

    @Recoverable(beforeMethod = "addBefore", recoverMethod = "addRecover")
    override fun add(p: Int, value: T) = addImpl(p, value)

    protected open fun addImpl(p: Int, value: T): Boolean {
        val newNode = recoveryData[p].value!!.node.value!!
        while (true) {
            val prevNext = findPrevNext(value)
            val previous = prevNext.previous
            val next = prevNext.next
            if (next != null && next.value.compareTo(value) == 0) {
                recoveryData[p].value!!.result.value = false
                return false
            }
            newNode.next[next] = false
            if (previous == null) {
                if (head.compareAndSet(next, newNode)) {
                    recoveryData[p].value!!.result.value = true
                    return true
                }
            } else {
                if (previous.next.compareAndSet(next, newNode, false, false)) {
                    recoveryData[p].value!!.result.value = true
                    return true
                }
            }
        }
    }

    protected open fun addBefore(p: Int, value: T) {
        checkPointer[p].value = 0
        checkPointer[p].flush()
        recoveryData[p].value = Info(nonVolatile(Node(value, null)))
        recoveryData[p].flush()
        checkPointer[p].value = 1
        checkPointer[p].flush()
    }

    protected open fun addRecover(p: Int, value: T): Boolean {
        if (checkPointer[p].value == 0) return addImpl(p, value)
        val node = recoveryData[p].value!!.node.value!!
        val result = recoveryData[p].value!!.result.value
        if (result != null) return result
        val prevNext = findPrevNext(value)
        val current = prevNext.next
        if (current === node || node.next.isMarked) {
            recoveryData[p].value!!.result.value = true
            return true
        }
        return addImpl(p, value)
    }

    @Recoverable(beforeMethod = "removeBefore", recoverMethod = "removeRecover")
    override fun remove(p: Int, value: T) = removeImpl(p, value)

    protected open fun removeImpl(p: Int, value: T): Boolean {
        val prevNext = findPrevNext(value)
        val previous = prevNext.previous
        val current = prevNext.next
        if (current == null || current.value.compareTo(value) != 0) {
            recoveryData[p].value!!.result.value = false
            return false
        }
        recoveryData[p].value!!.node.value = current
        recoveryData[p].value!!.node.flush()
        while (!current.next.isMarked) {
            val next = current.next.reference
            current.next.compareAndSet(next, next, false, true)
        }
        val next = current.next.reference
        previous?.next?.compareAndSet(current, next, false, false)
            ?: head.compareAndSet(current, next)
        val result = current.deleter.compareAndSet(NULL_DELETER, p)
        current.deleter.flush()
        recoveryData[p].value!!.result.value = result
        return result
    }

    protected open fun removeBefore(p: Int, value: T) {
        checkPointer[p].value = 0
        checkPointer[p].flush()
        recoveryData[p].value = Info()
        recoveryData[p].flush()
        checkPointer[p].value = 1
        checkPointer[p].flush()
    }

    protected open fun removeRecover(p: Int, value: T): Boolean {
        if (checkPointer[p].value == 0) return removeImpl(p, value)
        val result = recoveryData[p].value!!.result.value
        if (result != null) return result
        val node = recoveryData[p].value!!.node.value
        if (node != null && node.next.isMarked) {
            node.deleter.compareAndSet(NULL_DELETER, p)
            node.deleter.flush()
            val res = node.deleter.value == p
            recoveryData[p].value!!.result.value = res
            return res
        }
        return removeImpl(p, value)
    }

    @Recoverable
    override operator fun contains(value: T): Boolean {
        var current = head.get()
        val isDeleted = booleanArrayOf(false)
        while (current != null && current.value <= value) {
            val next = current.next[isDeleted]
            if (current.value.compareTo(value) == 0 && !isDeleted[0]) {
                return true
            }
            current = next
        }
        return false
    }
}

@Param(name = "key", gen = IntGen::class, conf = "0:3")
internal abstract class SetFailingTest :
    AbstractNVMLincheckFailingTest(Recover.NRL, THREADS_NUMBER, SequentialSet::class) {
    protected abstract val set: RecoverableSet<Int>

    @Operation
    fun add(@Param(gen = ThreadIdGen::class) threadId: Int, @Param(name = "key") key: Int) = set.add(threadId, key)

    @Operation
    fun remove(@Param(gen = ThreadIdGen::class) threadId: Int, @Param(name = "key") key: Int) =
        set.remove(threadId, key)

    @Operation
    fun contains(@Param(name = "key") key: Int) = set.contains(key)
    override val expectedExceptions = listOf(NullPointerException::class)
}

internal class SetFailingTest1 : SetFailingTest() {
    override val set = NRLFailingSet1<Int>(THREADS_NUMBER + 2)
}

internal class SetFailingTest2 : SetFailingTest() {
    override val set = NRLFailingSet2<Int>(THREADS_NUMBER + 2)
}

internal class SetFailingTest3 : SetFailingTest() {
    override val set = NRLFailingSet3<Int>(THREADS_NUMBER + 2)
}

internal class SetFailingTest4 : SetFailingTest() {
    override val set = NRLFailingSet4<Int>(THREADS_NUMBER + 2)
}

internal class SetFailingTest5 : SetFailingTest() {
    override val set = NRLFailingSet5<Int>(THREADS_NUMBER + 2)
}

internal class SetFailingTest6 : SetFailingTest() {
    override val set = NRLFailingSet6<Int>(THREADS_NUMBER + 2)
}

internal class SetFailingTest7 : SetFailingTest() {
    override val set = NRLFailingSet7<Int>(THREADS_NUMBER + 2)
}

internal class SetFailingTest8 : SetFailingTest() {
    override val set = NRLFailingSet8<Int>(THREADS_NUMBER + 2)
}

internal class SetFailingTest9 : SetFailingTest() {
    override val set = NRLFailingSet9<Int>(THREADS_NUMBER + 2)
}

internal class NRLFailingSet1<T : Comparable<T>>(threadsCount: Int) : NRLSet<T>(threadsCount) {
    override fun addBefore(p: Int, value: T) {
        checkPointer[p].value = 0
        checkPointer[p].flush()
        recoveryData[p].value = Info(nonVolatile(Node(value, null)))
        // here should be recoveryData[p].flush()
        checkPointer[p].value = 1
        checkPointer[p].flush()
    }
}

internal class NRLFailingSet2<T : Comparable<T>>(threadsCount: Int) : NRLSet<T>(threadsCount) {
    override fun addImpl(p: Int, value: T): Boolean {
        val newNode = recoveryData[p].value!!.node.value!!
        while (true) {
            val prevNext = findPrevNext(value)
            val previous = prevNext.previous
            val next = prevNext.next
            if (next != null && next.value.compareTo(value) == 0) {
                recoveryData[p].value!!.result.value = false
                return false
            }
            // here should be newNode.next[next] = false
            if (previous == null) {
                if (head.compareAndSet(next, newNode)) {
                    recoveryData[p].value!!.result.value = true
                    return true
                }
            } else {
                if (previous.next.compareAndSet(next, newNode, false, false)) {
                    recoveryData[p].value!!.result.value = true
                    return true
                }
            }
        }
    }
}

internal class NRLFailingSet3<T : Comparable<T>>(threadsCount: Int) : NRLSet<T>(threadsCount) {
    override fun addBefore(p: Int, value: T) {
        checkPointer[p].value = 0
        checkPointer[p].flush()
        recoveryData[p].value = Info(nonVolatile(Node(value, null)))
        recoveryData[p].flush()
        checkPointer[p].value = 1
        // here should be checkPointer[p].flush()
    }
}

internal class NRLFailingSet4<T : Comparable<T>>(threadsCount: Int) : NRLSet<T>(threadsCount) {
    override fun addBefore(p: Int, value: T) {
        checkPointer[p].value = 0
        checkPointer[p].flush()
        recoveryData[p].value = Info(nonVolatile(Node(value, null)))
        // here should be recoveryData[p].flush()
        checkPointer[p].value = 1
        checkPointer[p].flush()
    }
}

internal class NRLFailingSet5<T : Comparable<T>>(threadsCount: Int) : NRLSet<T>(threadsCount) {
    override fun addRecover(p: Int, value: T): Boolean {
        if (checkPointer[p].value == 0) return addImpl(p, value)
        val node = recoveryData[p].value!!.node.value!!
        val result = recoveryData[p].value!!.result.value
        if (result != null) return result
        val prevNext = findPrevNext(value)
        val current = prevNext.next
        if (current === node || node.next.isMarked) {
            recoveryData[p].value!!.result.value = true
            return true
        }
        // here should be return addImpl(p, value)
        return false
    }
}

internal class NRLFailingSet6<T : Comparable<T>>(threadsCount: Int) : NRLSet<T>(threadsCount) {
    override fun removeImpl(p: Int, value: T): Boolean {
        val prevNext = findPrevNext(value)
        val previous = prevNext.previous
        val current = prevNext.next
        if (current == null /* here should be || current.value.compareTo(value) != 0 */) {
            recoveryData[p].value!!.result.value = false
            return false
        }
        recoveryData[p].value!!.node.value = current
        recoveryData[p].value!!.node.flush()
        while (!current.next.isMarked) {
            val next = current.next.reference
            current.next.compareAndSet(next, next, false, true)
        }
        val next = current.next.reference
        previous?.next?.compareAndSet(current, next, false, false)
            ?: head.compareAndSet(current, next)
        val result = current.deleter.compareAndSet(NULL_DELETER, p)
        current.deleter.flush()
        recoveryData[p].value!!.result.value = result
        return result
    }
}

internal class NRLFailingSet7<T : Comparable<T>>(threadsCount: Int) : NRLSet<T>(threadsCount) {
    override fun removeImpl(p: Int, value: T): Boolean {
        val prevNext = findPrevNext(value)
        val previous = prevNext.previous
        val current = prevNext.next
        if (current == null || current.value.compareTo(value) != 0) {
            recoveryData[p].value!!.result.value = false
            return false
        }
        recoveryData[p].value!!.node.value = current
        recoveryData[p].value!!.node.flush()
        while (!current.next.isMarked) {
            val next = current.next.reference
            current.next.compareAndSet(next, next, false, true)
        }
        val next = current.next.reference
        return previous?.next?.compareAndSet(current, next, false, false)
            ?: head.compareAndSet(current, next)
        // here should be
//        val result = current.deleter.compareAndSet(NULL_DELETER, p)
//        current.deleter.flush()
//        recoveryData[p].value!!.result.value = result
//        recoveryData[p].value!!.result.flush()
//        return result
    }
}

internal class NRLFailingSet8<T : Comparable<T>>(threadsCount: Int) : NRLSet<T>(threadsCount) {
    override fun removeImpl(p: Int, value: T): Boolean {
        val prevNext = findPrevNext(value)
        val previous = prevNext.previous
        val current = prevNext.next
        if (current == null || current.value.compareTo(value) != 0) {
            recoveryData[p].value!!.result.value = false
            return false
        }
        recoveryData[p].value!!.node.value = current
        recoveryData[p].value!!.node.flush()
        while (!current.next.isMarked) {
            val next = current.next.reference
            current.next.compareAndSet(next, next, false, true)
        }
        val next = current.next.reference
        previous?.next?.compareAndSet(current, next, false, false)
            ?: head.compareAndSet(current, next)
        val result = current.deleter.compareAndSet(NULL_DELETER, p)
        // here should be current.deleter.flush()
        recoveryData[p].value!!.result.value = result
        return result
    }
}

internal class NRLFailingSet9<T : Comparable<T>>(threadsCount: Int) : NRLSet<T>(threadsCount) {
    override fun removeRecover(p: Int, value: T): Boolean {
        if (checkPointer[p].value == 0) return removeImpl(p, value)
        val result = recoveryData[p].value!!.result.value
        if (result != null) return result
        val node = recoveryData[p].value!!.node.value
        if (node != null && node.next.isMarked) {
            // here should be node.deleter.compareAndSet(NULL_DELETER, p)
            node.deleter.value = p
            node.deleter.flush()
            val res = node.deleter.value == p
            recoveryData[p].value!!.result.value = res
            return res
        }
        return removeImpl(p, value)
    }
}
