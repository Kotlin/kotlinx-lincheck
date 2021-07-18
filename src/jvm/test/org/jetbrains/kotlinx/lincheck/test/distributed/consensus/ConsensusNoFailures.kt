package org.jetbrains.kotlinx.lincheck.test.distributed.consensus

import kotlinx.coroutines.sync.Semaphore
import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.DistributedOptions
import org.jetbrains.kotlinx.lincheck.distributed.Environment
import org.jetbrains.kotlinx.lincheck.distributed.MessageOrder
import org.jetbrains.kotlinx.lincheck.distributed.Node
import org.junit.Test
import java.util.concurrent.ThreadLocalRandom

sealed class Message
data class Offer(val value: Int, val initializer: Int) : Message()
data class Result(val value: Int) : Message()

class ConsensusNoFailures(val env: Environment<Message, Unit>) : Node<Message> {
    private val offers = Array(env.numberOfNodes) {
        mutableListOf<Offer>()
    }

    private val results = Array<Int?>(env.numberOfNodes) {
        null
    }

    private var ok: Boolean? = null

    private val semaphore = Semaphore(1, 1)

    override fun onMessage(message: Message, sender: Int) {
        when (message) {
            is Offer -> {
                val initializer = message.initializer
                if (offers[initializer].isEmpty()) {
                    val myOffer = Offer(nextValue(), initializer)
                    offers[initializer].add(myOffer)
                    env.broadcast(myOffer)
                }
                offers[initializer].add(message)
                check()
            }
            is Result -> {
                results[sender] = message.value
                checkConsensusIsCorrect()
            }
        }
    }

    private fun check() {
        // Check if all offers received and we can form the result.
        for (offs in offers) {
            if (offs.size != env.numberOfNodes) continue
            val res = offs.map { it.value }.minOrNull()
            val sender = offs[0].initializer
            if (sender != env.nodeId) {
                env.send(Result(res!!), sender)
            } else {
                results[env.nodeId] = res
                checkConsensusIsCorrect()
            }
            offs.clear()
        }
    }

    private fun checkConsensusIsCorrect() {
        // Check if all results received.
        if (!results.any { it == null }) {
            ok = results.all { it == results[0] }
            results.fill(null)
            semaphore.release()
        }
    }

    @Operation
    suspend fun startElection(): Boolean {
        ok = null
        val offer = Offer(nextValue(), initializer = env.nodeId)
        offers[env.nodeId].add(offer)
        env.broadcast(offer)
        semaphore.acquire()
        return ok!!
    }

    private fun nextValue() = ThreadLocalRandom.current().nextInt()
}

class Checker {
    suspend fun startElection() = true
}

class ConsensusNaiveTest {
    @Test
    fun test() {
        LinChecker.check(
            ConsensusNoFailures::class.java,
            DistributedOptions<Message, Unit>()
                .requireStateEquivalenceImplCheck(false)
                .sequentialSpecification(Checker::class.java)
                .threads(3)
                .actorsPerThread(3)
                .messageOrder(MessageOrder.FIFO)
                .invocationsPerIteration(30)
                .iterations(1000)
        )
    }
}