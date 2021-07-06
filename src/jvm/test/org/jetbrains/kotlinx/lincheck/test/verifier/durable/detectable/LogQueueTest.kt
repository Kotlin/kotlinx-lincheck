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

import org.jetbrains.kotlinx.lincheck.annotations.DurableRecoverAll
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.nvm.api.nonVolatile
import org.jetbrains.kotlinx.lincheck.paramgen.OperationIdGen
import org.jetbrains.kotlinx.lincheck.paramgen.ThreadIdGen
import org.jetbrains.kotlinx.lincheck.test.verifier.nlr.AbstractNVMLincheckFailingTest
import org.jetbrains.kotlinx.lincheck.test.verifier.nlr.AbstractNVMLincheckTest
import org.jetbrains.kotlinx.lincheck.verifier.VerifierState
import java.util.*
import kotlin.reflect.KClass

private const val THREADS_NUMBER = 2

internal interface RecoverableQueue {
    fun push(value: Int, threadId: Int, operationNumber: Int)
    fun pop(threadId: Int, operationNumber: Int): Int?
    fun recover()
}

/**
 * @see  <a href="http://www.cs.technion.ac.il/~erez/Papers/nvm-queue-full.pdf">A Persistent Lock-Free Queue for Non-Volatile Memory</a>
 */
internal class LogQueueTest :
    AbstractNVMLincheckTest(Recover.DETECTABLE_EXECUTION, THREADS_NUMBER, SequentialQueue::class, false) {
    private val q = LogQueue(THREADS_NUMBER + 2)

    @Operation
    fun push(
        @Param(gen = ThreadIdGen::class) threadId: Int,
        @Param(gen = OperationIdGen::class) operationNumber: Int
    ) = q.push(operationNumber, threadId, operationNumber)

    @Operation
    fun pop(
        @Param(gen = ThreadIdGen::class) threadId: Int,
        @Param(gen = OperationIdGen::class) operationNumber: Int
    ) = q.pop(threadId, operationNumber)

    @DurableRecoverAll
    fun recover() = q.recover()
}

internal class SequentialQueue : VerifierState() {
    private val q = ArrayDeque<Int>()
    override fun extractState() = q.toList()
    fun pop(threadId: Int, operationNumber: Int) = q.poll()
    fun push(threadId: Int, operationNumber: Int) {
        q.offer(operationNumber)
    }
}

internal class LogEntry(val operationNumber: Int, n: QueueNode?) {
    val status = nonVolatile(false)
    val node = nonVolatile(n)
}

internal class QueueNode(val v: Int) {
    val next = nonVolatile(null as QueueNode?)
    val logInsert = nonVolatile(null as LogEntry?)
    val logRemove = nonVolatile(null as LogEntry?)
}

internal open class LogQueue(threadsNumber: Int) : RecoverableQueue {
    protected val dummy = QueueNode(42)
    protected val head = nonVolatile(dummy)
    protected val tail = nonVolatile(dummy)
    protected val logs = List(threadsNumber) { nonVolatile(null as LogEntry?) }

    override fun push(value: Int, threadId: Int, operationNumber: Int) {
        // recover
        logs[threadId].value?.let { log ->
            if (log.operationNumber == operationNumber) {
                if (log.status.value) return
            }
        }
        val newNode = QueueNode(value)
        val log = LogEntry(operationNumber, newNode)
        newNode.logInsert.value = log
        newNode.logInsert.flush()
        logs[threadId].value = log
        logs[threadId].flush()
        while (true) {
            val last = tail.value
            val nextNode = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    last.next.flush()
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            }
        }
    }

    override fun pop(threadId: Int, operationNumber: Int): Int? {
        // recover
        logs[threadId].value?.let { log ->
            if (log.operationNumber == operationNumber) {
                if (log.status.value || log.node.value !== null) return log.node.value?.v
            }
        }
        val log = LogEntry(operationNumber, null)
        logs[threadId].value = log
        logs[threadId].flush()
        while (true) {
            val first = head.value
            val last = tail.value
            val nextNode = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) {
                    logs[threadId].value!!.status.value = true
                    logs[threadId].value!!.status.flush()
                    return null
                }
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue = nextNode.v
                if (nextNode.logRemove.compareAndSet(null, log)) {
                    first.next.value!!.logRemove.flush()
                    nextNode.logRemove.value!!.node.value = first.next.value
                    nextNode.logRemove.value!!.node.flush()
                    head.compareAndSet(first, nextNode)
                    return currentValue
                } else {
                    if (head.value === first) {
                        first.next.value!!.logRemove.flush()
                        nextNode.logRemove.value!!.node.value = first.next.value
                        nextNode.logRemove.value!!.node.flush()
                        head.compareAndSet(first, nextNode)
                    }
                }
            }
        }
    }

    override fun recover() {
        // In practice it would be faster to start with head.
        // To handle this, we have to flush logInsert before every head move.
        var v: QueueNode? = dummy
        while (v !== null) {
            v.logInsert.value?.status?.value = true
            v.logInsert.value?.status?.flush()
            if (v.logRemove.value !== null) {
                v.logRemove.flush()
                v.logRemove.value!!.node.value = v
                v.logRemove.value!!.node.flush()
            }
            v.next.flush()
            v = v.next.value
        }
        while (true) {
            val h = head.value
            val next = h.next.value ?: break
            if (next.logRemove.value === null) break
            head.compareAndSet(h, next)
        }
        while (true) {
            val t = tail.value
            val next = t.next.value ?: break
            t.next.flush()
            tail.compareAndSet(t, next)
        }
    }
}

internal abstract class LogQueueFailingTest :
    AbstractNVMLincheckFailingTest(Recover.DETECTABLE_EXECUTION, THREADS_NUMBER, SequentialQueue::class) {
    protected abstract val q: RecoverableQueue

    @Operation
    fun push(
        @Param(gen = ThreadIdGen::class) threadId: Int,
        @Param(gen = OperationIdGen::class) operationNumber: Int
    ) = q.push(operationNumber, threadId, operationNumber)

    @Operation
    fun pop(
        @Param(gen = ThreadIdGen::class) threadId: Int,
        @Param(gen = OperationIdGen::class) operationNumber: Int
    ) = q.pop(threadId, operationNumber)

    @DurableRecoverAll
    fun recover() = q.recover()
    override val expectedExceptions: List<KClass<out Throwable>> = listOf(IllegalStateException::class)
}

internal class LogQueueFailingTest1 : LogQueueFailingTest() {
    override val q = LogFailingQueue1(THREADS_NUMBER + 2)
}

internal class LogQueueFailingTest2 : LogQueueFailingTest() {
    override val q = LogFailingQueue2(THREADS_NUMBER + 2)
}

internal class LogQueueFailingTest3 : LogQueueFailingTest() {
    override val q = LogFailingQueue3(THREADS_NUMBER + 2)
}

internal class LogQueueFailingTest4 : LogQueueFailingTest() {
    override val q = LogFailingQueue4(THREADS_NUMBER + 2)
}

internal class LogQueueFailingTest5 : LogQueueFailingTest() {
    override val q = LogFailingQueue5(THREADS_NUMBER + 2)
}

internal class LogQueueFailingTest6 : LogQueueFailingTest() {
    override val q = LogFailingQueue6(THREADS_NUMBER + 2)
}

internal class LogQueueFailingTest7 : LogQueueFailingTest() {
    override val q = LogFailingQueue7(THREADS_NUMBER + 2)
}

internal class LogQueueFailingTest8 : LogQueueFailingTest() {
    override val q = LogFailingQueue8(THREADS_NUMBER + 2)
}

internal class LogQueueFailingTest9 : LogQueueFailingTest() {
    override val q = LogFailingQueue9(THREADS_NUMBER + 2)
}

internal class LogQueueFailingTest10 : LogQueueFailingTest() {
    override val q = LogFailingQueue10(THREADS_NUMBER + 2)
}

internal class LogQueueFailingTest11 : LogQueueFailingTest() {
    override val q = LogFailingQueue11(THREADS_NUMBER + 2)
}

internal class LogQueueFailingTest12 : LogQueueFailingTest() {
    override val q = LogFailingQueue12(THREADS_NUMBER + 2)
}

internal class LogQueueFailingTest13 : LogQueueFailingTest() {
    override val q = LogFailingQueue13(THREADS_NUMBER + 2)
}

internal class LogQueueFailingTest14 : LogQueueFailingTest() {
    override val q = LogFailingQueue14(THREADS_NUMBER + 2)
}

internal class LogFailingQueue1(threadsNumber: Int) : LogQueue(threadsNumber) {
    override fun push(value: Int, threadId: Int, operationNumber: Int) {
        // here should be recover
//        logs[threadId].value?.let { log ->
//            if (log.operationNumber == operationNumber) {
//                if (log.status.value) return
//            }
//        }
        val newNode = QueueNode(value)
        val log = LogEntry(operationNumber, newNode)
        newNode.logInsert.value = log
        newNode.logInsert.flush()
        logs[threadId].value = log
        logs[threadId].flush()
        while (true) {
            val last = tail.value
            val nextNode = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    last.next.flush()
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            }
        }
    }
}

internal class LogFailingQueue2(threadsNumber: Int) : LogQueue(threadsNumber) {
    override fun push(value: Int, threadId: Int, operationNumber: Int) {
        // recover
        logs[threadId].value?.let { log ->
            // here should be
//            if (log.operationNumber == operationNumber) {
            if (log.status.value) return
//            }
        }
        val newNode = QueueNode(value)
        val log = LogEntry(operationNumber, newNode)
        newNode.logInsert.value = log
        newNode.logInsert.flush()
        logs[threadId].value = log
        logs[threadId].flush()
        while (true) {
            val last = tail.value
            val nextNode = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    last.next.flush()
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            }
        }
    }
}

internal class LogFailingQueue3(threadsNumber: Int) : LogQueue(threadsNumber) {
    override fun push(value: Int, threadId: Int, operationNumber: Int) {
        // recover
        logs[threadId].value?.let { log ->
            if (log.operationNumber == operationNumber) {
                if (log.status.value) return
            }
        }
        val newNode = QueueNode(value)
        val log = LogEntry(operationNumber, newNode)
        newNode.logInsert.value = log
        newNode.logInsert.flush()
        // here should be logs[threadId].value = log
        logs[threadId].flush()
        while (true) {
            val last = tail.value
            val nextNode = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    last.next.flush()
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            }
        }
    }
}

internal class LogFailingQueue4(threadsNumber: Int) : LogQueue(threadsNumber) {
    override fun push(value: Int, threadId: Int, operationNumber: Int) {
        // recover
        logs[threadId].value?.let { log ->
            if (log.operationNumber == operationNumber) {
                if (log.status.value) return
            }
        }
        val newNode = QueueNode(value)
        val log = LogEntry(operationNumber, newNode)
        newNode.logInsert.value = log
        newNode.logInsert.flush()
        logs[threadId].value = log
        // here should be logs[threadId].flush()
        while (true) {
            val last = tail.value
            val nextNode = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    last.next.flush()
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            }
        }
    }
}

internal class LogFailingQueue5(threadsNumber: Int) : LogQueue(threadsNumber) {
    override fun push(value: Int, threadId: Int, operationNumber: Int) {
        // recover
        logs[threadId].value?.let { log ->
            if (log.operationNumber == operationNumber) {
                if (log.status.value) return
            }
        }
        val newNode = QueueNode(value)
        val log = LogEntry(operationNumber, newNode)
        newNode.logInsert.value = log
        newNode.logInsert.flush()
        logs[threadId].value = log
        logs[threadId].flush()
        while (true) {
            val last = tail.value
            val nextNode = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    // here should be last.next.flush()
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            }
        }
    }
}

internal class LogFailingQueue6(threadsNumber: Int) : LogQueue(threadsNumber) {
    override fun push(value: Int, threadId: Int, operationNumber: Int) {
        // recover
        logs[threadId].value?.let { log ->
            if (log.operationNumber == operationNumber) {
                if (log.status.value) return
            }
        }
        val newNode = QueueNode(value)
        val log = LogEntry(operationNumber, newNode)
        newNode.logInsert.value = log
        newNode.logInsert.flush()
        logs[threadId].value = log
        logs[threadId].flush()
        while (true) {
            val last = tail.value
            val nextNode = last.next.value
            if (last !== tail.value) continue
            if (nextNode === null) {
                if (last.next.compareAndSet(null, newNode)) {
                    last.next.flush()
                    tail.compareAndSet(last, newNode)
                    return
                }
            } else {
                // here should be last.next.flush()
                tail.compareAndSet(last, nextNode)
            }
        }
    }
}

internal class LogFailingQueue7(threadsNumber: Int) : LogQueue(threadsNumber) {
    override fun pop(threadId: Int, operationNumber: Int): Int? {
        // recover
        logs[threadId].value?.let { log ->
            // here should be if (log.operationNumber == operationNumber) {
            if (log.status.value || log.node.value !== null) return log.node.value?.v
            // }
        }
        val log = LogEntry(operationNumber, null)
        logs[threadId].value = log
        logs[threadId].flush()
        while (true) {
            val first = head.value
            val last = tail.value
            val nextNode = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) {
                    logs[threadId].value!!.status.value = true
                    logs[threadId].value!!.status.flush()
                    return null
                }
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue = nextNode.v
                if (nextNode.logRemove.compareAndSet(null, log)) {
                    first.next.value!!.logRemove.flush()
                    nextNode.logRemove.value!!.node.value = first.next.value
                    nextNode.logRemove.value!!.node.flush()
                    head.compareAndSet(first, nextNode)
                    return currentValue
                } else {
                    if (head.value === first) {
                        first.next.value!!.logRemove.flush()
                        nextNode.logRemove.value!!.node.value = first.next.value
                        nextNode.logRemove.value!!.node.flush()
                        head.compareAndSet(first, nextNode)
                    }
                }
            }
        }
    }
}

internal class LogFailingQueue8(threadsNumber: Int) : LogQueue(threadsNumber) {
    override fun pop(threadId: Int, operationNumber: Int): Int? {
        // recover
        // here should be
//        logs[threadId].value?.let { log ->
//            if (log.operationNumber == operationNumber) {
//                if (log.status.value || log.node.value !== null) return log.node.value?.v
//            }
//        }
        val log = LogEntry(operationNumber, null)
        logs[threadId].value = log
        logs[threadId].flush()
        while (true) {
            val first = head.value
            val last = tail.value
            val nextNode = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) {
                    logs[threadId].value!!.status.value = true
                    logs[threadId].value!!.status.flush()
                    return null
                }
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue = nextNode.v
                if (nextNode.logRemove.compareAndSet(null, log)) {
                    first.next.value!!.logRemove.flush()
                    nextNode.logRemove.value!!.node.value = first.next.value
                    nextNode.logRemove.value!!.node.flush()
                    head.compareAndSet(first, nextNode)
                    return currentValue
                } else {
                    if (head.value === first) {
                        first.next.value!!.logRemove.flush()
                        nextNode.logRemove.value!!.node.value = first.next.value
                        nextNode.logRemove.value!!.node.flush()
                        head.compareAndSet(first, nextNode)
                    }
                }
            }
        }
    }
}

internal class LogFailingQueue9(threadsNumber: Int) : LogQueue(threadsNumber) {
    override fun recover() {
        // In practice it would be faster to start with head.
        // To handle this, we have to flush logInsert before every head move.
        var v: QueueNode? = dummy
        while (v !== null) {
            // here should be v.logInsert.value?.status?.value = true
            v.logInsert.value?.status?.flush()
            if (v.logRemove.value !== null) {
                v.logRemove.flush()
                v.logRemove.value!!.node.value = v
                v.logRemove.value!!.node.flush()
            }
            v.next.flush()
            v = v.next.value
        }
        while (true) {
            val h = head.value
            val next = h.next.value ?: break
            if (next.logRemove.value === null) break
            head.compareAndSet(h, next)
        }
        while (true) {
            val t = tail.value
            val next = t.next.value ?: break
            t.next.flush()
            tail.compareAndSet(t, next)
        }
    }
}

internal class LogFailingQueue10(threadsNumber: Int) : LogQueue(threadsNumber) {
    override fun pop(threadId: Int, operationNumber: Int): Int? {
        // recover
        logs[threadId].value?.let { log ->
            if (log.operationNumber == operationNumber) {
                if (log.status.value || log.node.value !== null) return log.node.value?.v
            }
        }
        val log = LogEntry(operationNumber, null)
        logs[threadId].value = log
        // here should be logs[threadId].flush()
        while (true) {
            val first = head.value
            val last = tail.value
            val nextNode = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) {
                    logs[threadId].value!!.status.value = true
                    logs[threadId].value!!.status.flush()
                    return null
                }
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue = nextNode.v
                if (nextNode.logRemove.compareAndSet(null, log)) {
                    first.next.value!!.logRemove.flush()
                    nextNode.logRemove.value!!.node.value = first.next.value
                    nextNode.logRemove.value!!.node.flush()
                    head.compareAndSet(first, nextNode)
                    return currentValue
                } else {
                    if (head.value === first) {
                        first.next.value!!.logRemove.flush()
                        nextNode.logRemove.value!!.node.value = first.next.value
                        nextNode.logRemove.value!!.node.flush()
                        head.compareAndSet(first, nextNode)
                    }
                }
            }
        }
    }
}

internal class LogFailingQueue11(threadsNumber: Int) : LogQueue(threadsNumber) {
    override fun recover() {
        // here should be var v: QueueNode? = dummy
        var v: QueueNode? = head.value
        while (v !== null) {
            v.logInsert.value?.status?.value = true
            v.logInsert.value?.status?.flush()
            if (v.logRemove.value !== null) {
                v.logRemove.flush()
                v.logRemove.value!!.node.value = v
                v.logRemove.value!!.node.flush()
            }
            v.next.flush()
            v = v.next.value
        }
        while (true) {
            val h = head.value
            val next = h.next.value ?: break
            if (next.logRemove.value === null) break
            head.compareAndSet(h, next)
        }
        while (true) {
            val t = tail.value
            val next = t.next.value ?: break
            t.next.flush()
            tail.compareAndSet(t, next)
        }
    }
}

internal class LogFailingQueue12(threadsNumber: Int) : LogQueue(threadsNumber) {
    override fun pop(threadId: Int, operationNumber: Int): Int? {
        // recover
        logs[threadId].value?.let { log ->
            if (log.operationNumber == operationNumber) {
                if (log.status.value || log.node.value !== null) return log.node.value?.v
            }
        }
        val log = LogEntry(operationNumber, null)
        logs[threadId].value = log
        logs[threadId].flush()
        while (true) {
            val first = head.value
            val last = tail.value
            val nextNode = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) {
                    logs[threadId].value!!.status.value = true
                    logs[threadId].value!!.status.flush()
                    return null
                }
                // here should be last.next.flush()
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue = nextNode.v
                if (nextNode.logRemove.compareAndSet(null, log)) {
                    first.next.value!!.logRemove.flush()
                    nextNode.logRemove.value!!.node.value = first.next.value
                    nextNode.logRemove.value!!.node.flush()
                    head.compareAndSet(first, nextNode)
                    return currentValue
                } else {
                    if (head.value === first) {
                        first.next.value!!.logRemove.flush()
                        nextNode.logRemove.value!!.node.value = first.next.value
                        nextNode.logRemove.value!!.node.flush()
                        head.compareAndSet(first, nextNode)
                    }
                }
            }
        }
    }
}

internal class LogFailingQueue13(threadsNumber: Int) : LogQueue(threadsNumber) {
    override fun pop(threadId: Int, operationNumber: Int): Int? {
        // recover
        logs[threadId].value?.let { log ->
            if (log.operationNumber == operationNumber) {
                if (log.status.value || log.node.value !== null) return log.node.value?.v
            }
        }
        val log = LogEntry(operationNumber, null)
        logs[threadId].value = log
        logs[threadId].flush()
        while (true) {
            val first = head.value
            val last = tail.value
            val nextNode = first.next.value
            if (first !== head.value) continue
            if (first === last) {
                if (nextNode === null) {
                    logs[threadId].value!!.status.value = true
                    logs[threadId].value!!.status.flush()
                    return null
                }
                last.next.flush()
                tail.compareAndSet(last, nextNode)
            } else {
                checkNotNull(nextNode)
                val currentValue = nextNode.v
                if (nextNode.logRemove.compareAndSet(null, log)) {
                    // here should be first.next.value!!.logRemove.flush()
                    nextNode.logRemove.value!!.node.value = first.next.value
                    nextNode.logRemove.value!!.node.flush()
                    head.compareAndSet(first, nextNode)
                    return currentValue
                } else {
                    if (head.value === first) {
                        first.next.value!!.logRemove.flush()
                        nextNode.logRemove.value!!.node.value = first.next.value
                        nextNode.logRemove.value!!.node.flush()
                        head.compareAndSet(first, nextNode)
                    }
                }
            }
        }
    }
}

internal class LogFailingQueue14(threadsNumber: Int) : LogQueue(threadsNumber) {
    override fun recover() {
        // In practice it would be faster to start with head.
        // To handle this, we have to flush logInsert before every head move.
        var v: QueueNode? = dummy
        while (v !== null) {
            v.logInsert.value?.status?.value = true
            v.logInsert.value?.status?.flush()
            if (v.logRemove.value !== null) {
                v.logRemove.flush()
                //here should be v.logRemove.value!!.node.value = v
                v.logRemove.value!!.node.flush()
            }
            v.next.flush()
            v = v.next.value
        }
        while (true) {
            val h = head.value
            val next = h.next.value ?: break
            if (next.logRemove.value === null) break
            head.compareAndSet(h, next)
        }
        while (true) {
            val t = tail.value
            val next = t.next.value ?: break
            t.next.flush()
            tail.compareAndSet(t, next)
        }
    }
}
