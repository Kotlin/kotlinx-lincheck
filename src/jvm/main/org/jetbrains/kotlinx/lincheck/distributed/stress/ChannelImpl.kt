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

package org.jetbrains.kotlinx.lincheck.distributed.stress

import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.atomicArrayOfNulls
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.kotlinx.lincheck.distributed.queue.LockFreeQueue
import org.jetbrains.kotlinx.lincheck.distributed.queue.RandomElementQueue
import java.util.concurrent.locks.ReentrantLock

class ChannelImpl<E>(val numberOfNodes: Int, queueInitializer: () -> LockFreeQueue<E>) {
    private val receiver = atomic<CancellableContinuation<Any>?>(null)
    private val queues = atomicArrayOfNulls<LockFreeQueue<E>>(numberOfNodes)
    private val queueIndices = RandomElementQueue<Int>()
    private val lock = ReentrantLock()

    init {
        repeat(numberOfNodes) {
            queues[it].lazySet(queueInitializer())
        }
    }

    fun send(x: E, from: Int) {
        lock.withLock {
            queues[from].value!!.put(x)
            queueIndices.put(from)
            //println("${Thread.currentThread().name} ${hashCode()}: Send $from")
            resumeReceiver()
        }
    }

    private fun resumeReceiver(): Boolean {
        //println("${Thread.currentThread().name} ${hashCode()}: Resume receiver ${receiver.value}")
        while (true) {
            val curReceiver = receiver.value ?: return false
            if (receiver.compareAndSet(curReceiver, null)) {
                val index = queueIndices.poll()
                //println("${Thread.currentThread().name} ${hashCode()}: Before resume receive $index $curReceiver")
                val e = queues[index!!].value!!.poll()
                curReceiver.resume(e!!)
                //println("${Thread.currentThread().name} ${hashCode()}: Resume receive $index")
                return true
            }
        }
    }

    suspend fun receive(): Any {
        lock.lock()
        val t = tryReceive()
        if (t != null) {
            lock.unlock()
            return t
        }
        return suspendCancellableCoroutine { cont ->
           // println("${Thread.currentThread().name} ${hashCode()}: Store continuation")
            receiver.lazySet(cont)
            lock.unlock()
        }
    }

    private fun tryReceive(): E? {
        val index = queueIndices.poll() ?: return null
       //println("${Thread.currentThread().name}: Try receive $index ${receiver.value}")
        return queues[index].value!!.poll()!!
    }
}

@OptIn(InternalCoroutinesApi::class)
private fun <T> CancellableContinuation<T>.resume(res: T): Boolean {
    val token = tryResume(res) ?: return false
    completeResume(token)
    return true
}