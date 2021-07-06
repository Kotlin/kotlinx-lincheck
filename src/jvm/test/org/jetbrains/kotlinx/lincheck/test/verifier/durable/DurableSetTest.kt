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

package org.jetbrains.kotlinx.lincheck.test.verifier.durable

import org.jetbrains.kotlinx.lincheck.annotations.DurableRecoverAll
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.NonVolatileRef
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.test.verifier.nlr.AbstractNVMLincheckFailingTest
import org.jetbrains.kotlinx.lincheck.test.verifier.nlr.AbstractNVMLincheckTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import java.util.concurrent.ConcurrentLinkedDeque
import kotlin.reflect.KClass

private const val THREADS_NUMBER = 3

internal interface DurableSet {
    fun add(key: Int): Boolean
    fun remove(key: Int): Boolean
    fun contains(key: Int): Boolean
    fun recover()
}

/**
 * @see  <a https://arxiv.org/pdf/1909.02852.pdf">Efficient Lock-Free Durable Sets</a>
 */
internal class DurableLinkFreeSetTest : AbstractNVMLincheckTest(Recover.DURABLE, THREADS_NUMBER, SequentialSet::class) {
    val s = DurableLinkFreeSet<Any>()

    @Operation
    fun add(key: Int) = s.add(key)

    @Operation
    fun remove(key: Int) = s.remove(key)

    @Operation
    fun contains(key: Int) = s.contains(key)

    @DurableRecoverAll
    fun recover() = s.recover()
}

class SequentialSet : VerifierState() {
    private val set = mutableSetOf<Int>()

    fun add(key: Int) = set.add(key)
    fun remove(key: Int) = set.remove(key)
    fun contains(key: Int) = set.contains(key)

    override fun extractState() = set.toList()
}


private const val MASK_LOW = 1
private const val MASK_HIGH = 2

internal data class NextRef<T>(val next: SetNode<T>?, val deleted: Boolean)

internal class SetNode<T>(val key: Int, val value: T? = null, nextNode: SetNode<T>?, valid: Int = 0) {
    val validityBits = nonVolatile(valid)
    val insertFlushFlag = nonVolatile(false)
    val deleteFlushFlag = nonVolatile(false)
    val nextRef = nonVolatile(NextRef(nextNode, false))

    private fun v1(metadata: Int) = (metadata and MASK_HIGH) ushr 1
    private fun v2(metadata: Int) = metadata and MASK_LOW

    fun isValid(metadata: Int = validityBits.value) = v1(metadata) == v2(metadata)

    fun makeValid() {
        val metadata = validityBits.value
        if (isValid(metadata)) return
        val v1 = v1(metadata)
        val newMetadata = v1 + (metadata and MASK_LOW.inv())
        validityBits.value = newMetadata
    }

    fun isMarked() = nextRef.value.deleted
}

private fun <T> flushDelete(node: SetNode<T>) {
    if (node.deleteFlushFlag.value) return
    node.nextRef.flush()
    node.deleteFlushFlag.value = true
}

private fun <T> flushInsert(node: SetNode<T>) {
    if (node.insertFlushFlag.value) return
    node.nextRef.flush()
    node.validityBits.flush()
    node.insertFlushFlag.value = true
}

internal open class DurableLinkFreeSet<T> : DurableSet {
    protected val head: NonVolatileRef<SetNode<T>>

    // non volatile allocation storage, stores nodes in any order
    protected val nodeStorage = ConcurrentLinkedDeque<SetNode<T>>()

    init {
        val mx = SetNode(Int.MAX_VALUE, null as T?, null)
        head = nonVolatile(SetNode(Int.MAX_VALUE, null as T?, mx))
    }

    protected open fun allocateNode(key: Int, value: T?, next: SetNode<T>?): SetNode<T> {
        return SetNode(key, value, next, MASK_HIGH).also { nodeStorage.add(it) }
    }

    protected open fun trimNext(pred: SetNode<T>, curr: SetNode<T>): Boolean {
        val nextRef = pred.nextRef.value
        flushDelete(curr)
        val res = pred.nextRef.compareAndSet(nextRef, NextRef(curr.nextRef.value.next, nextRef.deleted))
        if (res) {
            nodeStorage.remove(curr)
        }
        return res
    }

    protected fun find(key: Int): Pair<SetNode<T>, SetNode<T>> {
        var prev = head.value
        var curr = prev.nextRef.value.next
        while (true) {
            if (!curr!!.isMarked()) {
                if (curr.key >= key) break
                prev = curr
            } else {
                trimNext(prev, curr)
            }
            curr = curr.nextRef.value.next
        }
        return prev to curr!!
    }

    override fun add(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key == key) {
                curr.makeValid()
                flushInsert(curr)
                return false
            }
            val nextRef = pred.nextRef.value
            if (nextRef.next !== curr) continue
            val newNode = allocateNode(key, null, curr)
            if (pred.nextRef.compareAndSet(nextRef, NextRef(newNode, false))) {
                newNode.makeValid()
                flushInsert(newNode)
                return true
            }
        }
    }

    override fun remove(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key != key) return false
            val nextRef = curr.nextRef.value
            val deletedRef = NextRef(nextRef.next, true)
            curr.makeValid()
            if (curr.nextRef.compareAndSet(nextRef, deletedRef)) {
                trimNext(pred, curr)
                return true
            }
        }
    }

    override fun contains(key: Int): Boolean {
        var curr = head.value.nextRef.value.next!!
        while (curr.key < key) {
            curr = curr.nextRef.value.next!!
        }
        if (curr.key != key) return false
        if (curr.isMarked()) {
            flushDelete(curr)
            return false
        }
        curr.makeValid()
        flushInsert(curr)
        return true
    }

    override fun recover() = doRecover()

    private fun fastAdd(newNode: SetNode<T>) {
        val key = newNode.key
        retry@ while (true) {
            var prev = head.value
            var curr = prev.nextRef.value.next!!
            while (true) {
                val succ = curr.nextRef.value.next
                if (curr.key < key) {
                    prev = curr
                    curr = succ!!
                    assert(!succ.isMarked())
                    continue
                }
                assert(curr.key != key)
                val nextRef = prev.nextRef.value
                if (nextRef.next !== curr) continue
                newNode.nextRef.value = NextRef(curr, false)
                if (prev.nextRef.compareAndSet(nextRef, NextRef(newNode, false))) {
                    return
                } else {
                    continue@retry
                }
            }
        }
    }

    protected open fun doRecover() {
        val mx = SetNode(Int.MAX_VALUE, null as T?, null)
        head.value = SetNode(Int.MIN_VALUE, null as T?, mx)
        val toDelete = mutableListOf<SetNode<T>>()
        for (v in nodeStorage) {
            if (v.nextRef.value.next === null && v.isValid()) continue
            if (!v.isValid() || v.isMarked()) {
                v.makeValid()
                toDelete.add(v)
            } else {
                fastAdd(v)
            }
        }
        toDelete.forEach { nodeStorage.remove(it) }
    }
}

@Param(name = "key", gen = IntGen::class, conf = "0:3")
internal abstract class DurableLinkFreeSetFailingTest :
    AbstractNVMLincheckFailingTest(Recover.DURABLE, THREADS_NUMBER, SequentialSet::class) {
    internal abstract val s: DurableSet

    @Operation
    fun add(@Param(name = "key") key: Int) = s.add(key)

    @Operation
    fun remove(@Param(name = "key") key: Int) = s.remove(key)

    @Operation
    fun contains(@Param(name = "key") key: Int) = s.contains(key)

    @DurableRecoverAll
    fun recover() = s.recover()

    override val expectedExceptions: List<KClass<out Throwable>> = listOf(AssertionError::class)
}

internal class DurableLinkFreeSetNoRecoverFailingTest : DurableLinkFreeSetFailingTest() {
    override val s = object : DurableLinkFreeSet<Any>() {
        override fun recover() {}
    }
}

internal class DurableLinkFreeSetFailingTest1 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet1<Int>()
}

internal class DurableLinkFreeSetFailingTest2 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet2<Int>()
}

internal class DurableLinkFreeSetFailingTest3 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet3<Int>()
}

internal class DurableLinkFreeSetFailingTest4 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet4<Int>()
}

internal class DurableLinkFreeSetFailingTest5 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet5<Int>()
}

internal class DurableLinkFreeSetFailingTest6 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet6<Int>()
}

internal class DurableLinkFreeSetFailingTest7 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet7<Int>()
}

internal class DurableLinkFreeSetFailingTest8 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet8<Int>()
}

internal class DurableLinkFreeSetFailingTest9 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet9<Int>()
}

internal class DurableLinkFreeSetFailingTest10 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet10<Int>()
}

internal class DurableLinkFreeSetFailingTest11 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet11<Int>()
}

internal class DurableLinkFreeSetFailingTest12 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet12<Int>()
}

internal class DurableLinkFreeSetFailingTest13 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet13<Int>()
    override val expectedExceptions = listOf(NullPointerException::class)
}

internal class DurableLinkFreeSetFailingTest14 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet14<Int>()
}

internal class DurableLinkFreeSetFailingTest15 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet15<Int>()
}

internal class DurableLinkFreeSetFailingTest16 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet16<Int>()
}

internal class DurableLinkFreeSetFailingTest17 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet17<Int>()
}

internal class DurableLinkFreeSetFailingTest18 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet18<Int>()
}

internal class DurableLinkFreeSetFailingTest19 : DurableLinkFreeSetFailingTest() {
    override val s = DurableLinkFreeFailingSet19<Int>()
}

internal class DurableLinkFreeFailingSet1<T> : DurableLinkFreeSet<T>() {
    override fun add(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key == key) {
                curr.makeValid()
                // here should be flushInsert(curr)
                return false
            }
            val nextRef = pred.nextRef.value
            if (nextRef.next !== curr) continue
            val newNode = allocateNode(key, null, curr)
            if (pred.nextRef.compareAndSet(nextRef, NextRef(newNode, false))) {
                newNode.makeValid()
                flushInsert(newNode)
                return true
            }
        }
    }
}


internal class DurableLinkFreeFailingSet2<T> : DurableLinkFreeSet<T>() {
    override fun add(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key == key) {
                curr.makeValid()
                flushInsert(curr)
                return false
            }
            val nextRef = pred.nextRef.value
            if (nextRef.next !== curr) continue
            val newNode = allocateNode(key, null, curr)
            if (pred.nextRef.compareAndSet(nextRef, NextRef(newNode, false))) {
                newNode.makeValid()
                // here should be flushInsert(newNode)
                return true
            }
        }
    }
}

internal class DurableLinkFreeFailingSet3<T> : DurableLinkFreeSet<T>() {
    override fun add(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key == key) {
                // here should be curr.makeValid()
                flushInsert(curr)
                return false
            }
            val nextRef = pred.nextRef.value
            if (nextRef.next !== curr) continue
            val newNode = allocateNode(key, null, curr)
            if (pred.nextRef.compareAndSet(nextRef, NextRef(newNode, false))) {
                newNode.makeValid()
                flushInsert(newNode)
                return true
            }
        }
    }
}

internal class DurableLinkFreeFailingSet4<T> : DurableLinkFreeSet<T>() {
    override fun add(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key == key) {
                curr.makeValid()
                flushInsert(curr)
                return false
            }
            val nextRef = pred.nextRef.value
            if (nextRef.next !== curr) continue
            val newNode = allocateNode(key, null, curr)
            if (pred.nextRef.compareAndSet(nextRef, NextRef(newNode, false))) {
                // here should be newNode.makeValid()
                flushInsert(newNode)
                return true
            }
        }
    }
}

internal class DurableLinkFreeFailingSet5<T> : DurableLinkFreeSet<T>() {
    override fun add(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key == key) {
                curr.makeValid()
                flushInsert(curr)
                return false
            }
            val nextRef = pred.nextRef.value
            if (nextRef.next !== curr) continue
            val newNode = allocateNode(key, null, curr)
            // here should be if (pred.nextRef.compareAndSet(nextRef, NextRef(newNode, false))) {
            pred.nextRef.value = NextRef(newNode, false)
            newNode.makeValid()
            flushInsert(newNode)
            return true
            //}
        }
    }
}

internal class DurableLinkFreeFailingSet6<T> : DurableLinkFreeSet<T>() {
    override fun add(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            // here should be
//            if (curr.key == key) {
//                curr.makeValid()
//                flushInsert(curr)
//                return false
//            }
            val nextRef = pred.nextRef.value
            if (nextRef.next !== curr) continue
            val newNode = allocateNode(key, null, curr)
            if (pred.nextRef.compareAndSet(nextRef, NextRef(newNode, false))) {
                newNode.makeValid()
                flushInsert(newNode)
                return true
            }
        }
    }
}

internal class DurableLinkFreeFailingSet7<T> : DurableLinkFreeSet<T>() {
    override fun trimNext(pred: SetNode<T>, curr: SetNode<T>): Boolean {
        val nextRef = pred.nextRef.value
        // here should be flushDelete(curr)
        val res = pred.nextRef.compareAndSet(nextRef, curr.nextRef.value)
        if (res) {
            nodeStorage.remove(curr)
        }
        return res
    }
}

internal class DurableLinkFreeFailingSet8<T> : DurableLinkFreeSet<T>() {
    override fun trimNext(pred: SetNode<T>, curr: SetNode<T>): Boolean {
        val nextRef = pred.nextRef.value
        flushDelete(curr)
        val res = pred.nextRef.compareAndSet(nextRef, curr.nextRef.value)
        // here should be
//        if (res) {
//            nodeStorage.remove(curr)
//        }
        nodeStorage.remove(curr)
        return res
    }
}

internal class DurableLinkFreeFailingSet9<T> : DurableLinkFreeSet<T>() {
    override fun allocateNode(key: Int, value: T?, next: SetNode<T>?): SetNode<T> {
        return SetNode(key, value, next, /* here should be MASK_HIGH */ MASK_LOW).also { nodeStorage.add(it) }
    }
}

internal class DurableLinkFreeFailingSet10<T> : DurableLinkFreeSet<T>() {
    override fun remove(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key != key) return false
            val nextRef = curr.nextRef.value
            val deletedRef = NextRef(nextRef.next, true)
            // here should be curr.makeValid()
            if (curr.nextRef.compareAndSet(nextRef, deletedRef)) {
                trimNext(pred, curr)
                return true
            }
        }
    }
}

internal class DurableLinkFreeFailingSet11<T> : DurableLinkFreeSet<T>() {
    override fun remove(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key != key) return false
            val nextRef = curr.nextRef.value
            val deletedRef = NextRef(nextRef.next, true)
            curr.makeValid()
            if (curr.nextRef.compareAndSet(nextRef, deletedRef)) {
                // here should be trimNext(pred, curr)
                return true
            }
        }
    }
}

internal class DurableLinkFreeFailingSet12<T> : DurableLinkFreeSet<T>() {
    override fun remove(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            if (curr.key != key) return false
            val nextRef = curr.nextRef.value
            val deletedRef = NextRef(nextRef.next, true)
            curr.makeValid()
            // here should be
            // if (curr.nextRef.compareAndSet(nextRef, deletedRef)) {
            curr.nextRef.value = deletedRef
            trimNext(pred, curr)
            return true
            //}
        }
    }
}

internal class DurableLinkFreeFailingSet13<T> : DurableLinkFreeSet<T>() {
    override fun remove(key: Int): Boolean {
        while (true) {
            val (pred, curr) = find(key)
            // here should be if (curr.key != key) return false
            val nextRef = curr.nextRef.value
            val deletedRef = NextRef(nextRef.next, true)
            curr.makeValid()
            if (curr.nextRef.compareAndSet(nextRef, deletedRef)) {
                trimNext(pred, curr)
                return true
            }
        }
    }
}

internal class DurableLinkFreeFailingSet14<T> : DurableLinkFreeSet<T>() {
    override fun contains(key: Int): Boolean {
        var curr = head.value.nextRef.value.next!!
        while (curr.key < key) {
            curr = curr.nextRef.value.next!!
        }
        // here should be if (curr.key != key) return false
        if (curr.isMarked()) {
            flushDelete(curr)
            return false
        }
        curr.makeValid()
        flushInsert(curr)
        return true
    }
}

internal class DurableLinkFreeFailingSet15<T> : DurableLinkFreeSet<T>() {
    override fun contains(key: Int): Boolean {
        var curr = head.value.nextRef.value.next!!
        while (curr.key < key) {
            curr = curr.nextRef.value.next!!
        }
        if (curr.key != key) return false
        if (curr.isMarked()) {
            // here should be flushDelete(curr)
            return false
        }
        curr.makeValid()
        flushInsert(curr)
        return true
    }
}

internal class DurableLinkFreeFailingSet16<T> : DurableLinkFreeSet<T>() {
    override fun contains(key: Int): Boolean {
        var curr = head.value.nextRef.value.next!!
        while (curr.key < key) {
            curr = curr.nextRef.value.next!!
        }
        if (curr.key != key) return false
        if (curr.isMarked()) {
            flushDelete(curr)
            return false
        }
        // here should be curr.makeValid()
        flushInsert(curr)
        return true
    }
}

internal class DurableLinkFreeFailingSet17<T> : DurableLinkFreeSet<T>() {
    override fun contains(key: Int): Boolean {
        var curr = head.value.nextRef.value.next!!
        while (curr.key < key) {
            curr = curr.nextRef.value.next!!
        }
        if (curr.key != key) return false
        if (curr.isMarked()) {
            flushDelete(curr)
            return false
        }
        curr.makeValid()
        // here should be flushInsert(curr)
        return true
    }
}

internal class DurableLinkFreeFailingSet18<T> : DurableLinkFreeSet<T>() {
    override fun doRecover() {
        val mx = SetNode(Int.MAX_VALUE, null as T?, null)
        head.value = SetNode(Int.MIN_VALUE, null as T?, mx)
        val toDelete = mutableListOf<SetNode<T>>()
        for (v in nodeStorage) {
            if (v.nextRef.value.next === null && v.isValid()) continue
            if (!v.isValid() || v.isMarked()) {
                v.makeValid()
                toDelete.add(v)
            } else {
                // here should be fastAdd(v)
            }
        }
        toDelete.forEach { nodeStorage.remove(it) }
    }
}

internal class DurableLinkFreeFailingSet19<T> : DurableLinkFreeSet<T>() {
    override fun trimNext(pred: SetNode<T>, curr: SetNode<T>): Boolean {
        val nextRef = pred.nextRef.value
        flushDelete(curr)
        val res = pred.nextRef.compareAndSet(nextRef, curr.nextRef.value)
        if (res) {
            nodeStorage.remove(curr)
        }
        return res
    }
}
