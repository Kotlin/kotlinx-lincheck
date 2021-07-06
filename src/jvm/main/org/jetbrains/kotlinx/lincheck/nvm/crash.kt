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
package org.jetbrains.kotlinx.lincheck.nvm

import kotlinx.atomicfu.atomic
import java.util.concurrent.atomic.AtomicBoolean

/**
 * This exception is used to emulate system crash.
 * Must be ignored by user code, namely 'catch (e: Throwable)' constructions should pass this exception.
 */
abstract class CrashError(enableStackTrace: Boolean) : Throwable(null, null, false, enableStackTrace) {
    var actorIndex: Int = -1
    abstract val crashStackTrace: Array<StackTraceElement>
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CrashError) return false

        if (actorIndex != other.actorIndex) return false
        if (!crashStackTrace.contentEquals(other.crashStackTrace)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = actorIndex
        result = 31 * result + crashStackTrace.contentHashCode()
        return result
    }
}

class CrashErrorImpl : CrashError(true) {
    override val crashStackTrace: Array<StackTraceElement> get() = stackTrace
}

/** Proxy provided to minimize [fillInStackTrace] calls as it influence performance a lot. */
class CrashErrorProxy(private val ste: StackTraceElement?) : CrashError(false) {
    override val crashStackTrace get() = if (ste === null) emptyArray() else arrayOf(ste)
}

private data class SystemContext(
    val waitingThreads: Int,
    val threads: Int,
    val free: AtomicBoolean = AtomicBoolean(true)
)

object Crash {
    private val systemCrashOccurred = AtomicBoolean(false)
    private val context = atomic(SystemContext(0, 0))
    private var awaitSystemCrashBeforeThrow = true
    internal val threads get() = context.value.threads

    @Volatile
    var useProxyCrash = true

    @Volatile
    internal lateinit var barrierCallback: () -> Unit

    @JvmStatic
    fun isCrashed() = systemCrashOccurred.get()

    @JvmStatic
    fun resetAllCrashed() {
        systemCrashOccurred.compareAndSet(true, false)
    }

    /**
     * Crash simulation.
     * @throws CrashError
     */
    internal fun crash(threadId: Int, ste: StackTraceElement?, systemCrash: Boolean) {
        if (!systemCrash) NVMCache.crash(threadId)
        if (awaitSystemCrashBeforeThrow && systemCrash) awaitSystemCrash()
        val crash = createCrash(ste)
        NVMState.registerCrash(threadId, crash)
        throw crash
    }

    private fun createCrash(ste: StackTraceElement?) = if (useProxyCrash) CrashErrorProxy(ste) else CrashErrorImpl()

    /**
     * Random crash simulation. Produces a single thread crash or a system crash.
     * On a system crash a thread waits for other threads to reach this method call.
     */
    @JvmStatic
    fun possiblyCrash(className: String?, fileName: String?, methodName: String?, lineNumber: Int) {
        if (isWaitingSystemCrash() || Probability.shouldCrash()) {
            val ste = StackTraceElement(className, methodName, fileName, lineNumber)
            val systemCrash = isWaitingSystemCrash() || Probability.shouldSystemCrash()
            crash(NVMState.threadId(), ste, systemCrash)
        }
    }

    /**
     * Await for all active threads to access this point and crash the cache.
     */
    @JvmStatic
    fun awaitSystemCrash() = barrierCallback()

    private fun defaultAwaitSystemCrash() {
        var free: AtomicBoolean
        while (true) {
            val c = context.value
            val newWaiting = c.waitingThreads + 1
            free = if (c.waitingThreads == 0) AtomicBoolean(false) else c.free
            if (changeState(c, newWaiting == c.threads, c.threads, newWaiting, free)) break
        }
        while (!free.get());
    }

    internal fun onSystemCrash() {
        systemCrashOccurred.compareAndSet(false, true)
        NVMCache.systemCrash()
    }

    /** Should be called when thread finished. */
    fun exit(threadId: Int) {
        while (true) {
            val c = context.value
            val newThreads = c.threads - 1
            val isLast = c.waitingThreads == newThreads && c.waitingThreads > 0
            if (changeState(c, isLast, newThreads, c.waitingThreads, c.free)) break
        }
    }

    /** Should be called when thread started. */
    fun register(threadId: Int) {
        while (true) {
            val currentContext = context.value
            if (currentContext.waitingThreads != 0) continue
            if (context.compareAndSet(currentContext, currentContext.copy(threads = currentContext.threads + 1))) break
        }
    }

    fun reset(recoverModel: RecoverabilityModel) {
        awaitSystemCrashBeforeThrow = recoverModel.awaitSystemCrashBeforeThrow
        context.value = SystemContext(0, 0)
        resetAllCrashed()
    }

    fun resetDefault() {
        barrierCallback = { defaultAwaitSystemCrash() }
    }

    private fun isWaitingSystemCrash() = context.value.waitingThreads > 0

    private fun changeState(
        c: SystemContext,
        isLast: Boolean,
        newThreads: Int,
        newWaiting: Int,
        newFree: AtomicBoolean
    ): Boolean {
        var newW = newWaiting
        if (isLast) {
            onSystemCrash()
            newW = 0 // reset barrier
        }
        return context.compareAndSet(c, SystemContext(newW, newThreads, newFree)).also { success ->
            if (success && isLast)
                check(newFree.compareAndSet(false, true)) // open barrier
        }
    }
}
