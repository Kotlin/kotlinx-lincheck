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

import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Validate
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import org.junit.Test


class OptimisticMutexIncorrect(private val env: Environment<MutexMessage, Unit>) : Node<MutexMessage> {
    companion object {
        @Volatile
        private var optimisticIncorrect = 0
    }

    private val requested = BooleanArray(env.numberOfNodes)
    private var inCS = false
    private val semaphore = Semaphore(1, 1)

    override fun onMessage(message: MutexMessage, sender: Int) {
        when (message) {
            is Req -> {
                requested[sender] = true
            }
            is Rel -> {
                requested[sender] = false
            }
            else -> throw RuntimeException("Unexpected message type")
        }
        checkCSEnter()
    }

    @Validate
    fun validate() {
        val events = env.events().map { it.filterIsInstance<InternalEvent>() }
        val locks = events.flatMap { it.filter { it.message == "Lock" } }
        for (i in locks.indices) {
            for (j in 0..i) {
                check(locks[i].clock.happensBefore(locks[j].clock) || locks[j].clock.happensBefore(locks[i].clock))
            }
        }
    }

    private fun checkCSEnter() {
        if (!requested[env.nodeId] || inCS) return
        for (i in 0 until env.nodeId) if (requested[i]) return // give way for lower numbered
        inCS = true
        semaphore.release()
    }

    @Operation(cancellableOnSuspension = false, blocking = false)
    suspend fun lock(){
        check(!requested[env.nodeId])
        requested[env.nodeId] = true
        env.broadcast(Req(0, 0))
        checkCSEnter()
        if (env.numberOfNodes != 1) {
            semaphore.acquire()
        } else {
            inCS = true
        }
        check(inCS)
        env.recordInternalEvent("Lock")
    }

    @Operation(cancellableOnSuspension = false)
    fun unlock() {
        if (!inCS) return
        inCS = false
        requested[env.nodeId] = false
        env.recordInternalEvent("Unlock")
        env.broadcast(Rel(0))
    }
}


class OptimisticMutexIncorrectTest {
    @Test(expected = LincheckAssertionError::class)
    fun testSimple() {
        LinChecker.check(
            OptimisticMutexIncorrect::class.java,
            DistributedOptions<MutexMessage, Unit>()
                .requireStateEquivalenceImplCheck(false)
                .verifier(EpsilonVerifier::class.java)
                .threads(3)
                .actorsPerThread(3)
                .messageOrder(MessageOrder.FIFO)
                .invocationsPerIteration(10)
                .iterations(3000)
        )
    }
}
