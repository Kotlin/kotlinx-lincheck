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

import kotlinx.atomicfu.atomicArrayOfNulls
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder
import org.jetbrains.kotlinx.lincheck.distributed.queue.FastQueue
import org.jetbrains.kotlinx.lincheck.distributed.queue.LockFreeQueue
import org.jetbrains.kotlinx.lincheck.distributed.queue.RandomElementQueue
import java.util.concurrent.ThreadLocalRandom
import kotlin.random.Random


interface MessageChannel<E> {
    fun send(item: E)

    suspend fun receive(): E

    suspend fun close(): Boolean
}

class FifoChannel<E> : MessageChannel<E> {
    private val channel = Channel<E>(UNLIMITED)

    override fun send(item: E) {
        channel.offer(item)
    }

    override suspend fun receive(): E {
        val res = channel.receive()
        return res
    }

    override suspend fun close() = channel.close()
}

class AsynchronousChannel<E> : MessageChannel<E> {
    companion object {
        const val MIX_RATE = 1
    }

    private val random: ThreadLocal<Random> = Probability.rand
    private val channel = Channel<E>(UNLIMITED)

    override fun send(item: E) {
        channel.offer(item)
    }

    override suspend fun receive(): E {
        var item = channel.receive()
        val mixRate = random.get().nextInt(0, MIX_RATE + 1)
        repeat(mixRate) {
            channel.send(item)
            item = channel.poll()!!
        }
        return item
    }

    override suspend fun close(): Boolean = channel.close()
}


class ChannelHandler<E>(
    private val messageOrder: MessageOrder,
    private val numberOfNodes: Int
) {
    private fun createChannels(): Array<MessageChannel<E>> = when (messageOrder) {
        MessageOrder.FIFO -> Array(numberOfNodes) { FifoChannel() }
        /*MessageOrder.FIFO -> {
            val channel = FifoChannel<E>()
            Array(numberOfNodes) { channel }
        }*/
        MessageOrder.ASYNCHRONOUS -> Array(numberOfNodes) { AsynchronousChannel() }
    }

    private fun queueInit() : LockFreeQueue<E> = when (messageOrder) {
        MessageOrder.FIFO -> FastQueue()
        MessageOrder.ASYNCHRONOUS -> RandomElementQueue()
    }

    private val channels = atomicArrayOfNulls<Array<MessageChannel<E>>>(numberOfNodes)

    init {
        repeat(numberOfNodes) {
            channels[it].lazySet(createChannels())
        }
    }

    operator fun get(sender: Int, receiver: Int) = channels[receiver].value!![sender]

    fun reset(i: Int) {
        repeat(numberOfNodes) {
            channels[it].lazySet(createChannels())
        }
    }
}