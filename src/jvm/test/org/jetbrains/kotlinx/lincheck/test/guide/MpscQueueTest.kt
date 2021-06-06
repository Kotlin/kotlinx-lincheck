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

package org.jetbrains.kotlinx.lincheck.test.guide

import org.jctools.queues.atomic.MpscLinkedAtomicQueue
import org.jetbrains.kotlinx.lincheck.annotations.*
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.junit.Test
import java.util.*

@OpGroupConfig(name = "consumer", nonParallel = true)
public class MpscQueueTest {
    private val queue = MpscLinkedAtomicQueue<Int>()

    @Operation
    public fun offer(x: Int) = queue.offer(x)

    @Operation(group = "consumer")
    public fun poll(): Int? = queue.poll()

    @Operation(group = "consumer")
    public fun peek(): Int? = queue.peek()

    @Test
    fun stressTest() = StressOptions()
        .sequentialSpecification(SequentialQueue::class.java)
        .check(this::class)
}

class SequentialQueue {
    val q = LinkedList<Int>()

    fun offer(x: Int) = q.offer(x)
    fun poll() = q.poll()
    fun peek() = q.peek()
}