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

import kotlinx.atomicfu.AtomicRef
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.atomicArrayOfNulls


class FastQueue<E> : LockFreeQueue<E> {
    private val tail: AtomicRef<Segment<E>>
    private val head: AtomicRef<Segment<E>>

    init {
        val segment = Segment<E>(null)
        segment.enqIdx.value = 0
        tail = atomic(segment)
        head = atomic(segment)
    }

    override fun put(item: E) {
        while (true) {
            val tmpTail = tail.value
            val idx = tmpTail.enqIdx.getAndIncrement()
            if (idx >= BUFFER_SIZE) {
                if (tail.value != tmpTail) continue
                val tmpNext = tmpTail.next.value
                if (tmpNext == null) {
                    val newSegment = Segment(item)
                    if (tmpTail.next.compareAndSet(null, newSegment)) {
                        tail.compareAndSet(tmpTail, newSegment)
                        return
                    }
                } else {
                    tail.compareAndSet(tmpTail, tmpNext)
                }
                continue
            }
            if (tmpTail.items[idx].compareAndSet(null, ValueItem(item))) {
                return
            }
        }
    }

    override fun poll(): E? {
        while (true) {
            val tmpHead = head.value
            if (tmpHead.deqIdx.value >= tmpHead.enqIdx.value && tmpHead.next.value == null) return null
            val idx = tmpHead.deqIdx.getAndIncrement()
            if (idx >= BUFFER_SIZE) {
                val tmpNext = tmpHead.next.value ?: return null
                head.compareAndSet(tmpHead, tmpNext)
                continue
            }
            val item = tmpHead.items[idx].getAndSet(Taken)
            if (item != null) {
                return (item as ValueItem<E>).value ?: continue
            }
        }
    }

    fun toList() : List<E> {
        val res = mutableListOf<E>()
        var cur = head.value
        while(true) {
            for (i in 0 until BUFFER_SIZE) {
                val item = cur.items[i].value
                if (item is ValueItem<*> && item.value != null) {
                    res.add((item as ValueItem<E>).value!!)
                }
            }
            cur = cur.next.value ?: return res
        }
    }
}


internal const val BUFFER_SIZE = 128

internal class Segment<E>(item: E?) {
    val enqIdx = atomic(1)
    val deqIdx = atomic(0)
    val items = atomicArrayOfNulls<SegmentItem<E>>(BUFFER_SIZE)

    val next = atomic<Segment<E>?>(null)

    init {
        items[0].lazySet(ValueItem(item))
    }
}

internal sealed class SegmentItem<out E>
internal class ValueItem<E>(val value: E?) : SegmentItem<E>()
internal object Taken : SegmentItem<Nothing>()

