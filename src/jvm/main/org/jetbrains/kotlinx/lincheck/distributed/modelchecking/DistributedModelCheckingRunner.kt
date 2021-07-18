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

package org.jetbrains.kotlinx.lincheck.distributed.modelchecking

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.createLincheckResult
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.jetbrains.kotlinx.lincheck.executeValidationFunctions
import org.jetbrains.kotlinx.lincheck.execution.ExecutionResult
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.execution.withEmptyClock
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.lang.NullPointerException
import java.lang.reflect.Method
import java.util.*

val debugLogs = mutableListOf<String>()

val debugOutput = true
fun addToFile(f: (BufferedWriter) -> Unit) {
    if (!debugOutput) return
    FileOutputStream("lamport_info.txt", true).bufferedWriter().use {
        f(it)
    }
}

class DistributedModelCheckingRunner<Message, Log>(
    strategy: DistributedModelCheckingStrategy<Message, Log>,
    val testCfg: DistributedCTestConfiguration<Message, Log>,
    testClass: Class<*>,
    validationFunctions: List<Method>,
    stateRepresentationFunction: Method?
) : Runner(
    strategy, testClass,
    validationFunctions,
    stateRepresentationFunction
) {
    private lateinit var testNodeExecutions: Array<TestNodeExecution>

    val tasks = mutableMapOf<Int, Task>()
    var curTreeNode: InterleavingTreeNode? = null
    val path = mutableListOf<InterleavingTreeNode>()
    var interleaving: Interleaving? = null
    val dispatcher = ModelCheckingDispatcher(this)
    var signal = Signal()
    lateinit var environments: Array<MCEnvironmentImpl<Message, Log>>
    val context = ModelCheckingContext(testCfg, scenario)
    val numberOfNodes = context.addressResolver.totalNumberOfNodes
    var exception: Throwable? = null
    val root: InterleavingTreeNode =
        InterleavingTreeNode(-1, context, OperationTask(-1, VectorClock(IntArray(numberOfNodes)), "start") {})
    var builder = InterleavingTreeBuilder(0, 0)
    var isInterrupted = false
    var maxNumberOfErrors = 0
    var numberOfFailures = 0
    var counter = 0

    override fun initialize() {
        super.initialize()
        testNodeExecutions = Array(context.addressResolver.nodesWithScenario) { t ->
            TestNodeExecutionGenerator.create(this, t, scenario.parallelExecution[t])
        }
        context.runner = this
    }

    fun isFullyExplored() = root.isFullyExplored

    fun addTask(task: Task, id: Int? = null) {
        val taskId = id ?: context.tasksId++
        //println("New task id $taskId $iNode $parentClock")
        //println("iNode=$iNode taskId=${taskId} curNode=${curTreeNode!!.taskId}")
        check(!tasks.containsKey(taskId))
        tasks[taskId] = task
    }

    fun bfsPrint() {
        val queue = LinkedList<Pair<Int, InterleavingTreeNode>>()
        var curId = 0
        queue.add(0 to root)
        while (queue.isNotEmpty()) {
            val e = queue.poll()
            if (e.first != curId) {
                curId = e.first
            }
            println("${e.second.id} ${e.second.task.clock} ${e.second.task.msg} next=${e.second.nextPossibleTasksIds}, filtered=${e.second.notCheckedTasks}, allFiltered=${e.second.allNotChecked}")
            queue.addAll(e.second.children.values.map { curId + 1 to it })
        }
        println()
    }

    fun reset() {
        // println()
        //bfsPrint()
        isInterrupted = false
        debugLogs.clear()
        curTreeNode = root
        path.clear()
        tasks.clear()
        builder = InterleavingTreeBuilder(maxNumberOfErrors - numberOfFailures, numberOfFailures)
        context.reset()
        environments = Array(numberOfNodes) {
            MCEnvironmentImpl(it, numberOfNodes, context = context)
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
        //println(scenario)
    }

    private suspend fun runNode(iNode: Int) {
        if (iNode >= context.addressResolver.nodesWithScenario) {
            return
        }
        val testNodeExecution = context.testNodeExecutions[iNode]
        val scenarioSize = scenario.parallelExecution[iNode].size
        if (testNodeExecution.actorId == scenarioSize + 1) return
        if (testNodeExecution.actorId == scenarioSize) {
            //println("Run iNode=$iNode scenario finish")
            context.testInstances[iNode].onScenarioFinish()
            return
        }
        val i = testNodeExecution.actorId
        val actor = scenario.parallelExecution[iNode][i]
        context.events.add(
            iNode to
                    OperationStartEvent(
                        actor,
                        context.incClockAndCopy(iNode),
                        context.getStateRepresentation(iNode)
                    )
        )
        try {
            testNodeExecution.actorId++
            //println("Run iNode=$iNode opId=$i")
            val res = testNodeExecution.runOperation(i)
            testNodeExecution.results[i] = if (actor.method.returnType == Void.TYPE) {
                VoidResult
            } else {
                createLincheckResult(res)
            }
        } catch (e: Throwable) {
            if (e.javaClass in actor.handledExceptions) {
                context.testNodeExecutions[iNode].results[i] = createExceptionResult(e.javaClass)
            } else {
                onFailure(iNode, e)
                testNodeExecution.actorId = scenarioSize
            }
        }
        addTask(
            OperationTask(
                iNode,
                VectorClock(context.incClockAndCopy(iNode)),
                "Run node $iNode ${testNodeExecution.actorId}"
            ) {
                runNode(iNode)
            })
    }

    private suspend fun handleException(f: suspend () -> Unit): Boolean {
        return try {
            f()
            true
        } catch (e: Throwable) {
            if (exception == null) {
                exception = e
            }
            false
        }
    }

    private suspend fun executeTask(next: Int) {
        path.add(curTreeNode!!)
        signal = Signal()
        GlobalScope.launch(dispatcher + TaskContext()) {
            handleException {
                tasks[next]!!.f()
            }
        }
        signal.await()
        curTreeNode!!.finish(tasks)
        tasks.remove(next)
        //println("Task keys ${tasks.keys}")
    }

    fun nextTransition(): Int? {
        if (curTreeNode == null) return null
        val curTaskId = curTreeNode!!.id
        val index = interleaving!!.path.indexOf(curTaskId)
        if (index == -1 || index == interleaving!!.path.size - 1) return null
        return interleaving!!.path[index + 1]
    }

    override fun run(): InvocationResult {
        reset()
        tasks[root.id] = root.task
        val coroutine = GlobalScope.launch(dispatcher) {
            for (i in 0 until numberOfNodes) {
                context.incClock(i)
                context.testInstances[i].onStart()
            }
            for (i in 0 until numberOfNodes) {
                addTask(OperationTask(i, VectorClock(context.copyClock(i)), "Run $i") {
                    runNode(i)
                })
            }
            curTreeNode!!.finish(tasks)
            tasks.remove(-1)
            //println("Task keys ${tasks.keys}")
            interleaving = root.chooseNextInterleaving(builder)
            path.add(curTreeNode!!)
            debugLogs.add("path=${interleaving!!.path}")
            try {
                for (next in interleaving!!.path) {
                    //println(next)
                    // println(tasks)
                    curTreeNode = curTreeNode!![next]
                    executeTask(next)
                    if (exception != null) return@launch
                }
                while (tasks.isNotEmpty()) {
                    val next = curTreeNode!!.next()
                    //println("Next $next")
                    if (next == null) {
                        var up = curTreeNode
                        //println("Filtered tasks ${up?.allNotChecked}")
                        try {
                            while (!tasks.any { it.key in up!!.parent!!.nextPossibleTasksIds }) {
                                //println("Up ${up?.task} ${up?.id}")
                                up = up!!.parent
                            }
                        } catch (e: NullPointerException) {
                            //println("Up $up")
                            bfsPrint()
                            //println("Tasks $tasks")
                            exception = e
                            return@launch
                        }
                        //println("Up parent ${up?.parent?.task} ${up?.parent?.id}")
                        check(up!!.task !is NodeCrashTask)
                        counter++
                        //println("Remove ${up!!.id} from ${up.parent!!.id}")
                        up!!.parent!!.nextPossibleTasksIds.remove(up.id)
                        up!!.parent!!.children.remove(up.id)

                        while (tasks.isNotEmpty()) {
                            val next = tasks.minOfOrNull { it.key }!!
                            signal = Signal()
                            GlobalScope.launch(dispatcher + TaskContext()) {
                                handleException {
                                    tasks[next]!!.f()
                                }
                            }
                            signal.await()
                            if (exception != null) return@launch
                            tasks.remove(next)
                        }
                        isInterrupted = true
                        break
                    }
                    //println("Next is $next, curTree $curTreeNode")
                    curTreeNode = curTreeNode!![next]
                    executeTask(next)
                }
            } catch (e: NullPointerException) {
                bfsPrint()
                exception = e
                return@launch
            }
            /*while (curTreeNode != null) {
                //println("Before next")
                curTreeNode = curTreeNode!!.nextNode()
                //println(curTreeNode?.taskId)
                if (curTreeNode == null) break
                path.add(curTreeNode!!)
                val i = curTreeNode!!.taskId
                //println("Launching task $i")
                signal = Signal()
                GlobalScope.launch(dispatcher + TaskContext()) {
                    tasks[i]!!()
                }
                signal.await()
                //println("Before finish")
                curTreeNode!!.finish()
                //println("After finish")
                if (exception != null) return@launch
                //println("------------")
            }*/
        }
        try {
            runBlocking {
                withTimeout(testCfg.timeoutMs) {
                    coroutine.join()
                }
            }
        } catch (e: TimeoutCancellationException) {
            return DeadlockInvocationResult(emptyMap())
        }
        //println("Current tree")
        //bfsPrint()
        if (isInterrupted) {
            //println("Interrupted")
            //bfsPrint()
        }
        if (!isInterrupted && root.isExploredNow()) {
            if (numberOfFailures == maxNumberOfErrors ||
                (numberOfFailures == context.nodeCrashInfo.maxNumberOfFailedNodes && testCfg.supportRecovery == CrashMode.NO_RECOVERIES)
            ) {
                maxNumberOfErrors++
                numberOfFailures = 0
            } else {
                numberOfFailures++
            }
        }
        if (!isInterrupted) {
            path.reversed().forEach { it.updateStats() }
        }
        //println("Execution is over")
        //path.reversed().forEach { it.updateExplorationStatistics() }
        //root.resetExploration()
        environments.forEach { it.isFinished = true }
        if (exception != null) {
            return UnexpectedExceptionInvocationResult(exception!!)
        }
        repeat(numberOfNodes) {
            context.logs[it] = environments[it].log
        }
        //interleaving.forEach { print("[${it.taskId}: ${it.iNode}] ") }
        //println()
        //println(root.fractionUnexplored)

        if (!isInterrupted) {
            addToFile { it.appendLine(context.events.toString()) }
        }

        context.testInstances.forEach {
            executeValidationFunctions(it, validationFunctions) { functionName, exception ->
                val s = ExecutionScenario(
                    scenario.initExecution,
                    scenario.parallelExecution,
                    emptyList()
                )
                //context.events.forEach { println("[${it.first}]: ${it.second}") }
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

    override fun onFailure(iThread: Int, e: Throwable) {
        if (exception == null) {
            exception = e
        }
    }

    fun storeEventsToFile(failure: LincheckFailure) {
        if (testCfg.logFilename == null) return
        File(testCfg.logFilename).printWriter().use { out ->
            out.println(failure)
            out.println()
            context.events.toList().forEach { p ->
                out.println("${p.first} # ${p.second}")
            }
        }
    }
}