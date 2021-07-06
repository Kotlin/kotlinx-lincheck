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

import org.jetbrains.kotlinx.lincheck.nvm.api.AbstractNonVolatilePrimitive

/** Volatile cache of non-volatile memory emulation. */
object NVMCache {
    const val MAX_THREADS_NUMBER = 10

    private val cache = Array<SmartSet<AbstractNonVolatilePrimitive>?>(MAX_THREADS_NUMBER) { null }

    /** Flushes all local variables of thread. */
    internal fun flushAll() {
        val threadId = NVMState.threadId()
        val localCache = cache[threadId] ?: return
        localCache.forEach { it.flushInternal() }
        localCache.clear()
    }

    internal fun add(threadId: Int, variable: AbstractNonVolatilePrimitive) {
        val localCache = cache[threadId] ?: SmartSet<AbstractNonVolatilePrimitive>().also { cache[threadId] = it }
        localCache.add(variable)
    }

    internal fun remove(threadId: Int, variable: AbstractNonVolatilePrimitive) {
        val localCache = cache[threadId] ?: return
        localCache.remove(variable)
    }

    internal fun crash(threadId: Int) {
        val localCache = cache[threadId] ?: return
        localCache.clear()
    }

    internal fun systemCrash() {
        cache.filterNotNull().forEach { localCache ->
            localCache.forEach { it.systemCrash() }
            localCache.clear()
        }
    }

    internal fun clear() {
        for (i in cache.indices) {
            cache[i] = null
        }
    }

}
