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
package org.jetbrains.kotlinx.lincheck.runner

import kotlinx.atomicfu.atomicArrayOfNulls
import kotlinx.coroutines.CancellableContinuation
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.LockSupport
import kotlin.math.min

/**
 * This class executes a set of runnables in an internal thread pool.
 */
internal class FixedActiveThreadsExecutor(private val nThreads: Int, runnerHash: Int) {
    // null, a waiting TestThread, Runnable or SHUTDOWN for each thread
    private val tasks = atomicArrayOfNulls<Any>(nThreads)
    // null, the waiting in submitAndAwait thread, DONE or an exception for each thread
    private val results = atomicArrayOfNulls<Any>(nThreads)
    // how many iterations threads spend in active wait until parking
    private var spinCount = 40000
    // for adaptive spin count changing
    // whether a thread was parked since the previous submitAndWait call
    @Volatile
    private var wasParked: Boolean = false
    // increases by 1 if wasParked is set and decreases otherwise.
    // once the absolute value exceeds WAS_PARK_BALANCE_THRESHOLD,
    // a decision is made either to double or to halve spinCount.
    // this adaptiveness helps to use active waiting in case of multi-core processors
    // and to use parking in case of single-core processors.
    // WAS_PARK_BALANCE_THRESHOLD is used to negate dispersion between calls
    // because of e.g. verification.
    private var wasParkedBalance: Int = 0

    init {
        repeat(nThreads) { iThread ->
            TestThread(iThread, runnerHash, testThreadRoutine(iThread)).start()
        }
    }

    /**
     * Submits a set of tasks to the thread pool and waits until all of them are completed.
     * The number of tasks should be equal to [nThreads].
     * @throws TimeoutException if more than [timeoutMs] passed
     * @throws ExecutionException if an unexpected exception happened
     */
    fun submitAndAwait(tasks: Array<out Runnable>, timeoutMs: Long? = null) {
        check(tasks.size == nThreads)
        submitTasks(tasks)
        await(timeoutMs)
        updateAdaptiveSpinCount()
    }

    /**
     * Initiates shutting down of all threads in the thread pool.
     */
    fun shutdown() {
        // submit shutdown tasks unparking waiting threads
        submitTasks(Array(nThreads) { SHUTDOWN })
    }

    private fun submitTasks(tasks: Array<out Any>) {
        for (i in 0 until nThreads) {
            results[i].value = null
            submitTask(i, tasks[i])
        }
    }

    private fun updateAdaptiveSpinCount() {
        if (wasParked) {
            wasParked = false
            wasParkedBalance++
            if (wasParkedBalance >= WAS_PARK_BALANCE_THRESHOLD) {
                spinCount /= 2
                wasParkedBalance = 0
            }
        } else {
            wasParkedBalance--
            if (wasParkedBalance <= -WAS_PARK_BALANCE_THRESHOLD) {
                spinCount = min(spinCount * 2, MAX_SPIN_COUNT)
                wasParkedBalance = 0
            }
        }
    }

    private fun submitTask(iThread: Int, task: Any) {
        if (tasks[iThread].compareAndSet(null, task)) return
        // the CAS failed, which means that a test thread is parked in waiting
        // submit the task and unpark the test thread
        val thread = tasks[iThread].value as TestThread
        tasks[iThread].value = task
        LockSupport.unpark(thread)
    }

    private fun await(timeoutMs: Long?) {
        val deadline = if (timeoutMs != null) System.currentTimeMillis() + timeoutMs else null
        for (iThread in 0 until nThreads)
            awaitTask(iThread, deadline)
    }

    private fun awaitTask(iThread: Int, deadline: Long?) {
        val result = getResult(iThread, deadline)
        // check whether there was an exception
        if (result != DONE)
            throw ExecutionException(result as Throwable)
    }

    private fun getResult(iThread: Int, deadline: Long?): Any {
        // active wait until any result for a limited number of iterations
        spinWait { results[iThread].value }?.let {
            return it
        }
        // park until timeout or a result
        val currentThread = Thread.currentThread()
        if (results[iThread].compareAndSet(null, Thread.currentThread())) {
            while (results[iThread].value === currentThread) {
                if (deadline != null) {
                    val timeLeft = deadline - System.currentTimeMillis()
                    if (timeLeft <= 0) throw TimeoutException()
                    LockSupport.parkNanos(timeLeft * 1_000_000)
                } else {
                    LockSupport.park()
                }
            }
        }
        return results[iThread].value!!
    }

    private fun testThreadRoutine(iThread: Int) = Runnable {
        loop@while (true) {
            val task = getTask(iThread)
            if (task == SHUTDOWN) return@Runnable
            tasks[iThread].value = null // reset task
            val runnable = task as Runnable
            try {
                runnable.run()
            } catch(e: Throwable) {
                setResult(iThread, e)
                continue@loop
            }
            setResult(iThread, DONE)
        }
    }

    private fun getTask(iThread: Int): Any {
        // active wait until any task for a limited number of iterations
        spinWait { tasks[iThread].value }?.let {
            return it
        }
        // park until any result
        val currentThread = Thread.currentThread()
        if (tasks[iThread].compareAndSet(null, Thread.currentThread())) {
            while (tasks[iThread].value === currentThread) {
                LockSupport.park()
            }
        }
        return tasks[iThread].value!!
    }

    private fun setResult(iThread: Int, any: Any) {
        if (results[iThread].compareAndSet(null, any)) return
        // the CAS failed, which means that a thread is parked in waiting
        // set the result and unpark the waiting thread
        val thread = results[iThread].value as Thread
        results[iThread].value = any
        LockSupport.unpark(thread)
    }

    private inline fun spinWait(getter: () -> Any?): Any? {
        repeat(spinCount) {
            getter()?.let {
                return it
            }
        }
        wasParked = true
        return null
    }

    class TestThread(val iThread: Int, val runnerHash: Int, r: Runnable) : Thread(r) {
        var cont: CancellableContinuation<*>? = null
    }

    companion object {
        private val SHUTDOWN = Any()
        private val DONE = Any()
        private const val MAX_SPIN_COUNT = 1_000_000
        private const val WAS_PARK_BALANCE_THRESHOLD = 20
    }
}