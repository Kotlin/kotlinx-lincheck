/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import java.io.File
import java.lang.reflect.Method
import kotlin.random.Random

inline fun withProbability(probability: Double, func: () -> Unit) {
    val rand = Random.nextDouble(0.0, 1.0)
    if (rand <= probability) {
        func()
    }
}

open class DistributedRunner<Message, Log>(
    strategy: DistributedStrategy<Message, Log>,
    val testCfg: DistributedCTestConfiguration<Message, Log>,
    testClass: Class<*>,
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?
) : Runner(
    strategy, testClass,
    validationFunctions,
    stateRepresentationFunction
) {
    companion object {
        const val CONTEXT_SWITCH_PROBABILITY = 0.6
    }

    private val runnerHash = this.hashCode()
    private val context: DistributedRunnerContext<Message, Log> =
        DistributedRunnerContext(testCfg, scenario, runnerHash, stateRepresentationFunction)
    private lateinit var testNodeExecutions: Array<TestNodeExecution>
    private lateinit var environments: Array<EnvironmentImpl<Message, Log>>
    private val exception = atomic<Throwable?>(null)
    private val numberOfNodes = context.addressResolver.totalNumberOfNodes

    override fun initialize() {
        super.initialize()
        testNodeExecutions = Array(context.addressResolver.nodesWithScenario) { t ->
            TestNodeExecutionGenerator.create(this, t, scenario.parallelExecution[t])
        }
        context.runner = this
    }

    //TODO: Maybe a better way?
    private val handler = CoroutineExceptionHandler { _, _ -> }

    private fun NodeDispatcher.createScope(): CoroutineScope {
        return CoroutineScope(this + AlreadyIncrementedCounter() + handler)
    }

    private fun reset() {
        exception.lazySet(null)
        context.reset()
        environments = Array(numberOfNodes) {
            EnvironmentImpl(context, it, context.dispatchers[it])
        }
        context.testInstances = Array(numberOfNodes) {
            context.addressResolver[it].getConstructor(Environment::class.java)
                .newInstance(environments[it]) as Node<Message>
        }
        context.testNodeExecutions = testNodeExecutions
        context.testNodeExecutions.forEachIndexed { t, ex ->
            ex.testInstance = context.testInstances[t]
            val actors = scenario.parallelExecution[t].size
            ex.results = arrayOfNulls(actors)
            ex.actorId = 0
        }
    }

    override fun run(): InvocationResult {
        reset()
        repeat(numberOfNodes) { i ->
            val dispatcher = context.dispatchers[i]
            dispatcher.createScope().launch { runNode(i) }
            dispatcher.createScope().launch {
                receiveUnavailableNodes(i)
            }
            dispatcher.launchReceiveMessage(i)
        }
        try {
            runBlocking {
                withTimeout(testCfg.timeoutMs) {
                    context.taskCounter.signal.await()
                }
            }
        } catch (e: TimeoutCancellationException) {
            if (testNodeExecutions.any { it.results.any { r -> r == null } }) {
                context.dispatchers.forEach { it.shutdown() }
                return DeadlockInvocationResult(emptyMap())
            }
        }
        context.dispatchers.forEach { it.shutdown() }
        environments.forEach { it.isFinished = true }
        if (exception.value != null) {
            return UnexpectedExceptionInvocationResult(exception.value!!)
        }
        repeat(numberOfNodes) {
            context.logs[it] = environments[it].log
        }

        context.testInstances.forEach {
            executeValidationFunctions(it, validationFunctions) { functionName, exception ->
                val s = ExecutionScenario(
                    scenario.initExecution,
                    scenario.parallelExecution,
                    emptyList()
                )
                return ValidationFailureInvocationResult(s, functionName, exception)
            }
        }

        context.testNodeExecutions.zip(scenario.parallelExecution).forEach { it.first.setSuspended(it.second) }
        val parallelResultsWithClock = context.testNodeExecutions.mapIndexed { i, ex ->
            ex.results.map { it!!.withEmptyClock(numberOfNodes) }
        }
        val results = ExecutionResult(
            emptyList(), null, parallelResultsWithClock, super.constructStateRepresentation(),
            emptyList(), null
        )
        return CompletedInvocationResult(results)
    }

    private suspend fun handleException(iNode: Int, f: suspend () -> Unit) {
        try {
            f()
        } catch (_: CrashError) {
            onNodeFailure(iNode)
        } catch (e: Throwable) {
            onFailure(iNode, e)
        }
    }

    private suspend fun receiveMessages(i: Int, sender: Int) {
        val channel = context.messageHandler[sender, i]
        val testInstance = context.testInstances[i]
        while (true) {
            val e = channel.receive()
            //println("[$i]: Receive from ${e.first} ${e.second.message}")
            val m = e.second
            context.incClock(i)
            val clock = context.maxClock(i, m.clock)
            context.events.put(
                i to
                        MessageReceivedEvent(
                            m.message,
                            sender = e.first,
                            id = m.id,
                            clock = clock,
                            state = context.getStateRepresentation(i)
                        )
            )
            handleException(i) {
                testInstance.onMessage(m.message, e.first)
            }
            withProbability(CONTEXT_SWITCH_PROBABILITY) {
                yield()
            }
        }
    }

    private suspend fun receiveUnavailableNodes(i: Int) {
        val channel = context.failureNotifications[i]
        val testInstance = context.testInstances[i]
        while (true) {
            val p = channel.receive()
            context.incClock(i)
            val clock = context.maxClock(i, p.second)
            context.events.put(
                i to CrashNotificationEvent(
                    p.first,
                    clock,
                    context.testInstances[i].stateRepresentation()
                )
            )
            handleException(i) {
                testInstance.onNodeUnavailable(p.first)
            }
        }
    }

    private fun NodeDispatcher.launchReceiveMessage(i: Int) {
        repeat(numberOfNodes) {
            createScope().launch { receiveMessages(i, it) }
        }
        //createScope().launch { receiveMessages(i, 0) }
    }

    private suspend fun runNode(iNode: Int) {
        handleException(iNode) {
            context.dispatchers[iNode].runSafely {
                context.testInstances[iNode].onStart()
            }
            if (iNode >= context.addressResolver.nodesWithScenario) {
                return@handleException
            }
            val testNodeExecution = context.testNodeExecutions[iNode]
            val scenarioSize = scenario.parallelExecution[iNode].size
            while (testNodeExecution.actorId < scenarioSize) {
                val i = testNodeExecution.actorId
                val actor = scenario.parallelExecution[iNode][i]
                context.events.put(
                    iNode to
                            OperationStartEvent(
                                actor,
                                context.incClockAndCopy(iNode),
                                context.getStateRepresentation(iNode)
                            )
                )
                try {
                    testNodeExecution.actorId++
                    val res = if (!actor.blocking) context.dispatchers[iNode].runSafely {
                        testNodeExecution.runOperation(i)
                    } else testNodeExecution.runOperation(i)
                    testNodeExecution.results[i] = if (actor.method.returnType == Void.TYPE) {
                        VoidResult
                    } else {
                        createLincheckResult(res)
                    }
                } catch (e: Throwable) {
                    if (e is CrashError) {
                        throw e
                    }
                    if (e.javaClass in actor.handledExceptions) {
                        context.testNodeExecutions[iNode].results[i] = createExceptionResult(e.javaClass)
                    } else {
                        onFailure(iNode, e)
                        testNodeExecution.actorId = scenarioSize
                    }
                }
                withProbability(CONTEXT_SWITCH_PROBABILITY) {
                    yield()
                }
            }
            if (testNodeExecution.actorId == scenarioSize) {
                testNodeExecution.actorId++
                context.testInstances[iNode].onScenarioFinish()
            }
        }
    }

    override fun onFailure(iThread: Int, e: Throwable) {
        super.onFailure(iThread, e)
        if (exception.compareAndSet(null, e)) {
            context.taskCounter.signal.signal()
        }
    }

    suspend fun onNodeFailure(iNode: Int) {
        addToFile {
            it.appendLine("[$iNode]: Node crashed")
        }
        val clock = context.incClockAndCopy(iNode)
        context.events.put(
            iNode to NodeCrashEvent(
                clock,
                context.testInstances[iNode].stateRepresentation()
            )
        )
        context.failureNotifications.filterIndexed { index, _ -> index != iNode }.forEach {
            it.send(iNode to clock)
        }
        context.dispatchers[iNode].crash()
        environments[iNode].isFinished = true
        context.testNodeExecutions.getOrNull(iNode)?.crash()
        environments[iNode].recordInternalEvent("${context.testNodeExecutions.getOrNull(iNode)?.results?.toList()}")
        if (testCfg.supportRecovery == CrashMode.ALL_NODES_RECOVER ||
            testCfg.supportRecovery == CrashMode.MIXED
            && context.probabilities[iNode].nodeRecovered()
        ) {
            val delta = context.initialTasksForNode
            context.taskCounter.add(delta)
            val logs = environments[iNode].log.toMutableList()
            context.messageHandler.reset(iNode)
            context.failureNotifications[iNode] = Channel(UNLIMITED)
            val dispatcher = NodeDispatcher(iNode, context.taskCounter, runnerHash, context.executors[iNode])
            environments[iNode] = EnvironmentImpl(context, iNode, dispatcher, logs)
            context.testInstances[iNode] =
                context.addressResolver[iNode].getConstructor(Environment::class.java)
                    .newInstance(environments[iNode]) as Node<Message>
            context.testNodeExecutions.getOrNull(iNode)?.testInstance = context.testInstances[iNode]
            context.dispatchers[iNode] = dispatcher
            dispatcher.createScope().launch {
                context.events.put(
                    iNode to ProcessRecoveryEvent(
                        context.incClockAndCopy(iNode),
                        context.testInstances[iNode].stateRepresentation()
                    )
                )
                handleException(iNode) {
                    context.testInstances[iNode].recover()
                    context.recoverNode(iNode)
                    runNode(iNode)
                }
            }
            dispatcher.launchReceiveMessage(iNode)
            dispatcher.createScope().launch {
                receiveUnavailableNodes(iNode)
            }
        } else {
            context.testNodeExecutions.getOrNull(iNode)?.crashRemained()
        }
        // suspendCoroutine<Unit> { }
    }

    override fun constructStateRepresentation(): String {
        return context.testInstances.mapIndexed { index, node ->
            index to stateRepresentationFunction?.let { getMethod(node, it) }
                ?.invoke(node) as String?
        }.filterNot { it.second.isNullOrBlank() }.joinToString(separator = "\n") { "STATE [${it.first}]: ${it.second}" }
    }

    fun storeEventsToFile(failure: LincheckFailure) {
        val printInstance = context.testCfg.nodeTypes.size > 1
        if (testCfg.logFilename == null) return
        File(testCfg.logFilename).printWriter().use { out ->
            out.println(failure)
            out.println()
            context.events.toList().forEach { p ->
                val header = if (printInstance) {
                    "${p.first}, ${context.addressResolver[p.first].simpleName}"
                } else {
                    p.first
                }
                out.println("[${header}]: ${p.second}")
            }
        }
    }

    override fun close() {
        super.close()
        context.executors.forEach { it.shutdown() }
    }
}
