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

package org.jetbrains.kotlinx.lincheck.distributed.queue

import kotlin.random.Random


class RandomElementQueue<E> : LockFreeQueue<E> {
    private val queue = FastQueue<E>()
    private val rand = ThreadLocal.withInitial { Random(Thread.currentThread().hashCode()) }

    override fun put(item: E) {
        queue.put(item)
    }

    override fun poll(): E? {
        var e: E? = queue.poll() ?: return null
        repeat(rand.get().nextInt(0, 3)) {
            queue.put(e!!)
            e = queue.poll()
            if (e == null) return null
        }
        return e
    }
}
