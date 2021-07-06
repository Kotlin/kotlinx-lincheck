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

package org.jetbrains.kotlinx.lincheck.nvm

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.runner.FixedActiveThreadsExecutor

private class InvalidThreadIdStateError(s: String) : Throwable(s)

internal enum class ExecutionState {
    INIT, PARALLEL, POST
}

object NVMState {
    @Volatile
    private var state = ExecutionState.INIT
    private var threads = 0

    @Volatile
    internal var crashesEnabled = false

    private var crashes = initCrashes()
    private val crashesCount = atomic(0)
    private val maxCrashesPerThread = atomic(0)
    private val executedActors = IntArray(NVMCache.MAX_THREADS_NUMBER) { -1 }

    private fun initCrashes() = Array(NVMCache.MAX_THREADS_NUMBER) { mutableListOf<CrashError>() }

    fun threadId(): Int = when (state) {
        ExecutionState.INIT -> 0
        ExecutionState.PARALLEL -> Thread.currentThread().let {
            if (it is FixedActiveThreadsExecutor.TestThread) return@let it.iThread + 1
            throw InvalidThreadIdStateError("State is PARALLEL but thread is not test thread.")
        }
        ExecutionState.POST -> threads + 1
    }

    internal fun registerCrash(threadId: Int, crash: CrashError) {
        crash.actorIndex = executedActors[threadId]
        crashes[threadId].add(crash)
        crashesCount.incrementAndGet()
        val myThreadCrashes = crashes[threadId].size
        while (true) {
            val curMax = maxCrashesPerThread.value
            if (curMax >= myThreadCrashes) break
            if (maxCrashesPerThread.compareAndSet(curMax, myThreadCrashes)) break
        }
    }

    internal fun clearCrashes() = crashes.also {
        crashesCount.value = 0
        maxCrashesPerThread.value = 0
        crashes = initCrashes()
        executedActors.indices.forEach { executedActors[it] = -1 }
    }

    private fun actorStarted(threadId: Int) {
        executedActors[threadId]++
    }

    fun crashesCount() = crashesCount.value
    fun maxCrashesCountPerThread() = maxCrashesPerThread.value

    fun onFinish(iThread: Int) {
        Crash.exit(iThread + 1)
    }

    fun onStart(iThread: Int) {
        Crash.register(iThread + 1)
    }

    fun reset(scenario: ExecutionScenario, recoverModel: RecoverabilityModel) {
        NVMCache.clear()
        Probability.reset(scenario, recoverModel)
        Crash.reset(recoverModel)
        Crash.resetDefault()
        state = ExecutionState.INIT
        crashesEnabled = false
        threads = 0
        clearCrashes()
    }

    fun beforeInit(recoverModel: RecoverabilityModel) {
        NVMCache.clear()
        Probability.setNewInvocation(recoverModel)
        Crash.reset(recoverModel)
        state = ExecutionState.INIT
        Crash.register(0)
        crashesEnabled = false
    }

    fun beforeParallel(threads: Int) {
        Crash.exit(0)
        NVMState.threads = threads
        state = ExecutionState.PARALLEL
        crashesEnabled = true
    }

    fun beforePost() {
        crashesEnabled = false
        state = ExecutionState.POST
        Crash.register(threads + 1)
    }

    fun afterPost() {
        Crash.exit(threads + 1)
    }

    fun onActorStart(iThread: Int) {
        actorStarted(iThread + 1)
    }

    fun onBeforeActorStart() {
        actorStarted(0)
    }

    fun onAfterActorStart() {
        actorStarted(threads + 1)
    }

    fun onEnterActorBody(iThread: Int, iActor: Int) = Statistics.onEnterActorBody(iThread + 1, iActor)
    fun onExitActorBody(iThread: Int, iActor: Int) = Statistics.onExitActorBody(iThread + 1, iActor)
}
