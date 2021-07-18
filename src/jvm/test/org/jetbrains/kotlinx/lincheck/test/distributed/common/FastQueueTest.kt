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

package org.jetbrains.kotlinx.lincheck.test.distributed.common

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.annotations.OpGroupConfig
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.queue.FastQueue
import org.jetbrains.kotlinx.lincheck.test.AbstractLincheckTest
import org.jetbrains.kotlinx.lincheck.verifier.EpsilonVerifier
import java.util.*
import java.util.concurrent.LinkedBlockingQueue


class IntQueue {
    val queue = LinkedBlockingQueue<Int>()
    fun put(i: Int) = queue.put(i)
    fun poll() = queue.poll()
}

@OpGroupConfig(name = "consumer", nonParallel = true)
class FastQueueTest : AbstractLincheckTest() {
    private val queue = LinkedBlockingQueue<Int>()

    @Operation
    fun put(value: Int) = queue.put(value)

    @Operation(group = "consumer")
    fun poll() = queue.poll()

    override fun <O : Options<O, *>> O.customize() {
        threads(3)
        actorsPerThread(3)
        requireStateEquivalenceImplCheck(false)
        sequentialSpecification(IntQueue::class.java)
        actorsBefore(0)
        actorsAfter(0)
       // verifier(EpsilonVerifier::class.java)
    }
}