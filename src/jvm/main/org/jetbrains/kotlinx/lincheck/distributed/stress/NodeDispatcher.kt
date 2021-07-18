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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import org.jetbrains.kotlinx.lincheck.distributed.Signal
import org.jetbrains.kotlinx.lincheck.distributed.stress.NodeDispatcher.Companion.NodeDispatcherStatus.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Counts the total number of pending tasks for the [NodeDispatcher] to execute.
 * Signals to the main thread when the execution is other.
 */
class DispatcherTaskCounter(
    initialCounter: Int
) {
    private val counter = atomic(initialCounter)
    val signal = Signal()

    /**
     * Signals to the waiting thread if there are no tasks left.
     */
    private fun checkIsFinished() {
        if (counter.value == 0) {
            signal.signal()
        }
    }

    /**
     * Returns the current number of tasks.
     */
    fun get() = counter.value

    /**
     * Increments the number of tasks.
     */
    fun increment() = counter.incrementAndGet()

    /**
     * Decrements the number of pending tasks.
     * If no tasks left, signals to the waiting thread.
     */
    fun decrement() = counter.decrementAndGet().also { checkIsFinished() }

    /**
     * Adds [delta] to the current number of tasks.
     */
    fun add(delta: Int) = counter.addAndGet(delta)

    /**
     * Awaits until the execution is over.
     */
    suspend fun await() = signal.await()

    /**
     * Signals to the waiting thread.
     */
    fun signal() = signal.signal()
}

/**
 * Indicates that the counter [DispatcherTaskCounter] for this coroutine was already incremented
 * and should not be incremented inside [NodeDispatcher]. Stops being taken into account after the first time,
 * as the counter was incremented only for the initial task.
 */
class AlreadyIncrementedCounter : AbstractCoroutineContextElement(Key) {
    @Volatile
    var isUsed = false

    companion object Key : CoroutineContext.Key<AlreadyIncrementedCounter>
}

/**
 * The dispatcher for executing task related to a single [Node] inside [org.jetbrains.kotlinx.lincheck.distributed.stress.DistributedRunner].
 */
class NodeDispatcher(val id: Int,
                     private val taskCounter: DispatcherTaskCounter,
                     private val runnerHash: Int,
                     private val executor: ExecutorService) :
    CoroutineDispatcher() {
    companion object {
        enum class NodeDispatcherStatus { RUNNING, STOPPED, CRASHED }
    }

    private val status = atomic(RUNNING)
    private var nodeOperationCounter = 0

    /**
     * Thread to run tasks related to a specified node.
     */
    class NodeTestThread(val iThread: Int, val runnerHash: Int, r: Runnable) :
        Thread(r, "NodeExecutor@$runnerHash-$iThread")

    /**
     * Executed a given [block]. The task counter is incremented if necessary when the task is submitted and
     * decremented when the execution of task is finished.
     */
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (status.value == STOPPED) return
        if (context[AlreadyIncrementedCounter.Key]?.isUsed != false) {
            taskCounter.increment()
        } else {
            context[AlreadyIncrementedCounter.Key]?.isUsed = true
        }
        try {
            executor.submit {
                if (status.value == RUNNING) block.run()
                if (status.value != STOPPED) taskCounter.decrement()
            }
        } catch (_: RejectedExecutionException) {
            return
        }
    }

    /**
     * Sets status to CRASHED and clears the counter for this thread.
     */
    fun crash() {
        status.lazySet(CRASHED)
        clear()
    }

    /**
     * Runs the given suspended function [f] and guarantees that
     * the execution will not finish before the function is executed or the
     * node crash occurs.
     *
     * To achieve it, the task counter is incremented before calling [f] and decremented after [f] is executed.
     * However, if [f] suspends and when crash occurs, the finally-block will never be executed and the total sum will be greater than zero.
     * To handle it, the extra thread-local counter [nodeOperationCounter] is used (see [clear]).
     */
    suspend fun <T> runSafely(f: suspend () -> T): T {
        taskCounter.increment()
        nodeOperationCounter++
        try {
            return f()
        } finally {
            if (nodeOperationCounter > 0) {
                taskCounter.decrement()
                nodeOperationCounter--
            }
        }
    }


    /**
     * Shutdowns the executor.
     */
    fun shutdown() {
        status.lazySet(STOPPED)
       // executor.shutdown()
    }

    /**
     * Clears all suspended functions which were called inside [runSafely].
     * Called when the node crash occurs. The total counter is decreased by the value of the local counter [nodeOperationCounter],
     * and the local counter is set to zero.
     */
    private fun clear() {
        taskCounter.add(-nodeOperationCounter)
        nodeOperationCounter = 0
    }
}