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
package org.jetbrains.kotlinx.lincheck.nvm.api

import kotlinx.atomicfu.atomic
import org.jetbrains.kotlinx.lincheck.nvm.NVMCache
import org.jetbrains.kotlinx.lincheck.nvm.NVMState
import org.jetbrains.kotlinx.lincheck.nvm.Probability

abstract class AbstractNonVolatilePrimitive {
    internal abstract fun flushInternal()
    internal abstract fun systemCrash()

    fun flush() {
        flushInternal()
        NVMCache.remove(NVMState.threadId(), this)
    }

    /**
     * Random flush may occur on write to NVM, so the value is flushed or added to the cache.
     */
    protected fun addToCache() {
        if (Probability.shouldFlush()) {
            flushInternal()
        } else {
            NVMCache.add(NVMState.threadId(), this)
        }
    }
}

fun nonVolatile(value: Int) = NonVolatileInt(value)
fun nonVolatile(value: Long) = NonVolatileLong(value)
fun nonVolatile(value: Boolean) = NonVolatileBoolean(value)
fun <T> nonVolatile(value: T) = NonVolatileRef(value)

/** Persistent reference emulates non-volatile memory variable with volatile cache. */
class NonVolatileRef<T> internal constructor(initialValue: T) : AbstractNonVolatilePrimitive() {
    @Volatile
    private var nonVolatileValue = initialValue
    private val volatileValue = atomic(initialValue)

    var value: T
        get() = volatileValue.value
        set(_value) {
            volatileValue.value = _value
            addToCache()
        }

    override fun flushInternal() {
        nonVolatileValue = volatileValue.value
    }

    override fun systemCrash() {
        volatileValue.value = nonVolatileValue
    }

    fun setAndFlush(value: T) {
        volatileValue.value = value
        nonVolatileValue = value
    }

    fun compareAndSet(expect: T, update: T): Boolean =
        volatileValue.compareAndSet(expect, update).also { if (it) addToCache() }

    fun getAndSet(value: T): T = volatileValue.getAndSet(value).also { addToCache() }

    fun lazySet(value: T) {
        volatileValue.lazySet(value)
        addToCache()
    }
}

class NonVolatileInt internal constructor(initialValue: Int) : AbstractNonVolatilePrimitive() {
    @Volatile
    private var nonVolatileValue = initialValue
    private val volatileValue = atomic(initialValue)

    var value: Int
        get() = volatileValue.value
        set(_value) {
            volatileValue.value = _value
            addToCache()
        }

    override fun flushInternal() {
        nonVolatileValue = volatileValue.value
    }

    override fun systemCrash() {
        volatileValue.value = nonVolatileValue
    }

    fun setAndFlush(value: Int) {
        volatileValue.value = value
        nonVolatileValue = value
    }

    fun compareAndSet(expect: Int, update: Int): Boolean =
        volatileValue.compareAndSet(expect, update).also { if (it) addToCache() }

    fun getAndSet(value: Int) = volatileValue.getAndSet(value).also { addToCache() }

    fun lazySet(value: Int) {
        volatileValue.lazySet(value)
        addToCache()
    }

    fun getAndIncrement(): Int = volatileValue.getAndIncrement().also { addToCache() }
    fun getAndDecrement(): Int = volatileValue.getAndDecrement().also { addToCache() }
    fun incrementAndGet(): Int = volatileValue.incrementAndGet().also { addToCache() }
    fun decrementAndGet(): Int = volatileValue.decrementAndGet().also { addToCache() }
    fun getAndAdd(delta: Int): Int = volatileValue.getAndAdd(delta).also { addToCache() }
    fun addAndGet(delta: Int) = volatileValue.addAndGet(delta).also { addToCache() }

    operator fun plusAssign(delta: Int) {
        volatileValue.plusAssign(delta)
        addToCache()
    }

    operator fun minusAssign(delta: Int) {
        volatileValue.minusAssign(delta)
        addToCache()
    }
}


class NonVolatileLong internal constructor(initialValue: Long) : AbstractNonVolatilePrimitive() {
    @Volatile
    private var nonVolatileValue = initialValue
    private val volatileValue = atomic(initialValue)

    var value: Long
        get() = volatileValue.value
        set(_value) {
            volatileValue.value = _value
            addToCache()
        }

    override fun flushInternal() {
        nonVolatileValue = volatileValue.value
    }

    override fun systemCrash() {
        volatileValue.value = nonVolatileValue
    }

    fun setAndFlush(value: Long) {
        volatileValue.value = value
        nonVolatileValue = value
    }

    fun compareAndSet(expect: Long, update: Long): Boolean =
        volatileValue.compareAndSet(expect, update).also { if (it) addToCache() }

    fun getAndSet(value: Long) = volatileValue.getAndSet(value).also { addToCache() }

    fun lazySet(value: Long) {
        addToCache()
        volatileValue.lazySet(value)
    }

    fun getAndIncrement(): Long = volatileValue.getAndIncrement().also { addToCache() }
    fun getAndDecrement(): Long = volatileValue.getAndDecrement().also { addToCache() }
    fun incrementAndGet(): Long = volatileValue.incrementAndGet().also { addToCache() }
    fun decrementAndGet(): Long = volatileValue.decrementAndGet().also { addToCache() }
    fun getAndAdd(delta: Long): Long = volatileValue.getAndAdd(delta).also { addToCache() }
    fun addAndGet(delta: Long): Long = volatileValue.addAndGet(delta).also { addToCache() }

    operator fun plusAssign(delta: Long) {
        volatileValue.plusAssign(delta)
        addToCache()
    }

    operator fun minusAssign(delta: Long) {
        volatileValue.minusAssign(delta)
        addToCache()
    }
}

class NonVolatileBoolean internal constructor(initialValue: Boolean) : AbstractNonVolatilePrimitive() {
    @Volatile
    private var nonVolatileValue = initialValue
    private val volatileValue = atomic(initialValue)

    var value: Boolean
        get() = volatileValue.value
        set(_value) {
            volatileValue.value = _value
            addToCache()
        }

    override fun flushInternal() {
        nonVolatileValue = volatileValue.value
    }

    override fun systemCrash() {
        volatileValue.value = nonVolatileValue
    }

    fun setAndFlush(value: Boolean) {
        volatileValue.value = value
        nonVolatileValue = value
    }

    fun compareAndSet(expect: Boolean, update: Boolean): Boolean =
        volatileValue.compareAndSet(expect, update).also { if (it) addToCache() }

    fun getAndSet(value: Boolean) = volatileValue.getAndSet(value).also { addToCache() }

    fun lazySet(value: Boolean) {
        volatileValue.lazySet(value)
        addToCache()
    }
}
