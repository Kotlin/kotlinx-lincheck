/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.test.distributed.mutex

import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.suspendCoroutine


class SyncCoordinateMutex(private val env: Environment<MutexMessage, Unit>) : Node<MutexMessage> {
    companion object {
        @Volatile
        var syncCounter = 0
    }

    private val coordinatorId = 0
    private val isCoordinator = env.nodeId == coordinatorId
    private val queue = ArrayDeque<Int>()
    private var inCS = -1
    private val condition = Semaphore(1, 1)

    fun signal() {
        if (condition.availablePermits == 0) {
            condition.release()
        }
    }

    override fun onMessage(message: MutexMessage, sender: Int) {
        when (message) {
            is Req -> {
                check(isCoordinator)
                queue.addLast(sender)
                checkCSEnter()
            }
            is Ok -> {
                check(!isCoordinator)
                inCS = env.nodeId
                signal()
            }
            is Rel -> {
                check(isCoordinator)
                check(inCS == sender)
                inCS = -1
                checkCSEnter()
            }
        }
    }

    private fun checkCSEnter() {
        if (inCS != -1) return
        val id = queue.pollFirst() ?: return
        inCS = id
        if (id != coordinatorId) {
            env.send(Ok(0), id)
        } else {
            signal()
        }
    }

    @Validate
    fun validate() {
        syncCounter = 0
    }

    @Operation(cancellableOnSuspension = false, blocking = true)
    suspend fun lock() {
        if (inCS == env.nodeId) {
            suspendCoroutine<Unit> { }
        }
        //println("[${env.nodeId}]: Request lock")
        if (isCoordinator) {
            queue.add(coordinatorId)
            checkCSEnter()
        } else {
            env.send(Req(0, 0), coordinatorId)
        }
        if (inCS != env.nodeId) {
            condition.acquire()
        }
    }

    @Operation
    fun unlock() {
        //println("[${env.nodeId}]: Release lock")
        if(inCS != env.nodeId) return
        inCS = -1
        if (isCoordinator) {
            checkCSEnter()
        } else {
            env.send(Rel(0), coordinatorId)
        }
    }
}

class SyncCoordinateMutexTest {
    @Test
    fun testSimple() {
        LinChecker.check(
            SyncCoordinateMutex::class.java,
            DistributedOptions<MutexMessage, Unit>()
                .requireStateEquivalenceImplCheck(false)
                .sequentialSpecification(MutexSpecification::class.java)
                .threads(3)
                .actorsPerThread(3)
                .messageOrder(MessageOrder.FIFO)
                .invocationsPerIteration(3000)
                .iterations(10)
        )
    }

    @Test
    fun testNoFifo() {
        LinChecker.check(
            SyncCoordinateMutex::class.java,
            DistributedOptions<MutexMessage, Unit>()
                .requireStateEquivalenceImplCheck(false)
                .sequentialSpecification(MutexSpecification::class.java)
                .threads(3)
                .actorsPerThread(3)
                .messageOrder(MessageOrder.ASYNCHRONOUS)
                .invocationsPerIteration(3000)
                .iterations(10)
        )
    }
}