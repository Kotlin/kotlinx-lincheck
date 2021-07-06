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
package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking

import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.nvm.RecoverabilityModel
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import java.lang.reflect.*
import kotlin.math.max
import kotlin.random.*

/**
 * The model checking strategy studies all possible interleavings by increasing the
 * interleaving tree depth -- the number of context switches performed by the strategy.
 *
 * To restrict the number of interleaving to be studied, it is specified in [testCfg].
 * The strategy constructs an interleaving tree, where nodes choose where the next
 * context switch should be performed and to which thread.
 *
 * The strategy does not study the same interleaving twice.
 * The depth of the interleaving tree increases gradually when all possible
 * interleavings of the previous depth are studied. On the current level,
 * the interleavings are studied uniformly, to study as many different ones
 * as possible when the maximal number of interleavings to be studied is lower
 * than the number of all possible interleavings on the current depth level.
 */
internal class ModelCheckingStrategy(
        testCfg: ModelCheckingCTestConfiguration,
        testClass: Class<*>,
        scenario: ExecutionScenario,
        validationFunctions: List<Method>,
        stateRepresentation: Method?,
        verifier: Verifier,
        recoverModel: RecoverabilityModel
) : ManagedStrategy(testClass, scenario, verifier, validationFunctions, stateRepresentation, testCfg, recoverModel) {
    // The number of invocations that the strategy is eligible to use to search for an incorrect execution.
    private val maxInvocations = testCfg.invocationsPerIteration
    // The number of already used invocations.
    private var usedInvocations = 0
    // The maximum number of thread switch choices that strategy should perform
    // (increases when all the interleavings with the current depth are studied).
    private var maxNumberOfEvents = 0
    // The root of the interleaving tree that chooses the starting thread.
    private var root: InterleavingTreeNode = ThreadChoosingNode((0 until nThreads).toList())
    // This random is used for choosing the next unexplored interleaving node in the tree.
    private val generationRandom = Random(0)
    // The interleaving that will be studied on the next invocation.
    private lateinit var currentInterleaving: Interleaving

    override fun runImpl(): LincheckFailure? {
        while (usedInvocations < maxInvocations) {
            // get new unexplored interleaving
            currentInterleaving = root.nextInterleaving() ?: break
            usedInvocations++
            // run invocation and check its results
            checkResult(runInvocation())?.let { return it }
        }
        return null
    }

    override fun onNewSwitch(iThread: Int, mustSwitch: Boolean) {
        if (mustSwitch) {
            // Create new execution position if this is a forced switch.
            // All other execution positions are covered by `shouldSwitch` method,
            // but forced switches do not ask `shouldSwitch`, because they are forced.
            // a choice of this execution position will mean that the next switch is the forced one.
            currentInterleaving.newExecutionSwitchPosition(iThread)
        }
    }

    override fun onNewCrash(iThread: Int, mustCrash: Boolean) {
        if (mustCrash) {
            currentInterleaving.newExecutionCrashPosition(iThread)
        }
    }

    override fun shouldSwitch(iThread: Int): Boolean {
        // Crete a new current position in the same place as where the check is,
        // because the position check and the position increment are dual operations.
        check(iThread == currentThread)
        currentInterleaving.newExecutionSwitchPosition(iThread)
        return currentInterleaving.isSwitchPosition()
    }

    override fun shouldCrash(iThread: Int): Boolean {
        check(iThread == currentThread)
        currentInterleaving.newExecutionCrashPosition(iThread)
        return currentInterleaving.isCrashPosition()
    }

    override fun isSystemCrash(iThread: Int): Boolean {
        check(iThread == currentThread)
        return currentInterleaving.isSystemCrash()
    }

    override fun initializeInvocation() {
        currentInterleaving.initialize()
        super.initializeInvocation()
    }

    override fun chooseThread(iThread: Int): Int = currentInterleaving.chooseThread(iThread)

    /**
     * An abstract node with an execution choice in the interleaving tree.
     */
    private abstract inner class InterleavingTreeNode {
        private var fractionUnexplored = 1.0
        lateinit var choices: List<Choice>
        var isFullyExplored: Boolean = false
            protected set
        val isInitialized get() = ::choices.isInitialized

        fun nextInterleaving(): Interleaving? {
            if (isFullyExplored) {
                // Increase the maximum number of switches that can be used,
                // because there are no more not covered interleavings
                // with the previous maximum number of switches.
                maxNumberOfEvents++
                resetExploration()
            }
            // Check if everything is fully explored and there are no possible interleavings with more switches.
            if (isFullyExplored) return null
            return nextInterleaving(InterleavingBuilder())
        }

        abstract fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving

        protected fun resetExploration() {
            if (!isInitialized) {
                // This is a leaf node.
                isFullyExplored = false
                fractionUnexplored = 1.0
                return
            }
            choices.forEach { it.node.resetExploration() }
            updateExplorationStatistics()
        }

        fun finishExploration() {
            isFullyExplored = true
            fractionUnexplored = 0.0
        }

        protected fun updateExplorationStatistics() {
            check(isInitialized) { "An interleaving tree node was not initialized properly. " +
                    "Probably caused by non-deterministic behaviour (WeakHashMap, Object.hashCode, etc)" }
            if (choices.isEmpty()) {
                finishExploration()
                return
            }
            val total = choices.fold(0.0) { acc, choice ->
                acc + choice.node.fractionUnexplored
            }
            fractionUnexplored = total / choices.size
            isFullyExplored = choices.all { it.node.isFullyExplored }
        }

        protected fun chooseUnexploredNode(): Choice {
            if (choices.size == 1) return choices.first()
            // Choose a weighted random child.
            val total = choices.sumByDouble { it.node.fractionUnexplored }
            val random = generationRandom.nextDouble() * total
            var sumWeight = 0.0
            choices.forEach { choice ->
                sumWeight += choice.node.fractionUnexplored
                if (sumWeight >= random)
                    return choice
            }
            // In case of errors because of floating point numbers choose the last unexplored choice.
            return choices.last { !it.node.isFullyExplored }
        }
    }

    /**
     * Represents a choice of a thread that should be next in the execution.
     */
    private inner class ThreadChoosingNode(switchableThreads: List<Int>, moreCrashes: Boolean = true) : InterleavingTreeNode() {
        init {
            choices = switchableThreads.map {
                val child = if (recoverModel.crashes && moreCrashes) SwitchOrCrashChoosingNode() else SwitchChoosingNode()
                Choice(child, it)
            }
        }

        override fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving {
            val child = chooseUnexploredNode()
            interleavingBuilder.addThreadSwitchChoice(child.value)
            val interleaving = child.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }

    /**
     * Represents a choice of a position of a thread context switch.
     */
    private inner class SwitchChoosingNode : InterleavingTreeNode() {
        override fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving {
            val isLeaf = maxNumberOfEvents == interleavingBuilder.numberOfEvents
            if (isLeaf) {
                finishExploration()
                if (!isInitialized)
                    interleavingBuilder.addLastNoninitializedNode(this)
                return interleavingBuilder.build()
            }
            val choice = chooseUnexploredNode()
            interleavingBuilder.addSwitchPosition(choice.value)
            val interleaving = choice.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }

    private inner class CrashChoosingNode(private val isSystemCrash: Boolean) : InterleavingTreeNode() {
        override fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving {
            val isLeaf = maxNumberOfEvents == interleavingBuilder.numberOfEvents
            if (isLeaf) {
                finishExploration()
                if (!isInitialized)
                    interleavingBuilder.addLastNoninitializedNode(this)
                return interleavingBuilder.build()
            }
            val choice = chooseUnexploredNode()
            interleavingBuilder.addCrashPosition(choice.value, isSystemCrash)
            val interleaving = choice.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }

    private inner class SwitchOrCrashChoosingNode : InterleavingTreeNode() {
        init {
            choices = if (recoverModel.nonSystemCrashSupported())
                listOf(Choice(SwitchChoosingNode(), 0), Choice(CrashChoosingNode(true), 1), Choice(CrashChoosingNode(false), 2))
            else
                listOf(Choice(SwitchChoosingNode(), 0), Choice(CrashChoosingNode(true), 1))
        }

        override fun nextInterleaving(interleavingBuilder: InterleavingBuilder): Interleaving {
            val child = chooseUnexploredNode()
            val interleaving = child.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }

    private inner class Choice(val node: InterleavingTreeNode, val value: Int)

    /**
     * This class specifies an interleaving that is re-producible.
     */
    private inner class Interleaving(
        private val switchPositions: List<Int>,
        private val crashPositions: List<Int>,
        private val threadSwitchChoices: List<Int>,
        private val nonSystemCrashes: List<Int>,
        private var lastNotInitializedNode: InterleavingTreeNode?
    ) {
        private lateinit var interleavingFinishingRandom: Random
        private lateinit var nextThreadToSwitch: Iterator<Int>
        private var lastNotInitializedNodeChoices: MutableList<Choice>? = null
        private var executionPosition: Int = 0
        private lateinit var explorationType: ExplorationNodeType

        fun initialize() {
            executionPosition = -1 // the first execution position will be zero
            interleavingFinishingRandom = Random(2) // random with a constant seed
            nextThreadToSwitch = threadSwitchChoices.iterator()
            currentThread = nextThreadToSwitch.next() // choose initial executing thread
            lastNotInitializedNodeChoices = null
            explorationType = ExplorationNodeType.fromNode(lastNotInitializedNode)
            lastNotInitializedNode?.let {
                // Create a mutable list for the initialization of the not initialized node choices.
                lastNotInitializedNodeChoices = mutableListOf<Choice>().also { choices ->
                    it.choices = choices
                }
                lastNotInitializedNode = null
            }
        }

        fun chooseThread(iThread: Int): Int =
            if (nextThreadToSwitch.hasNext()) {
                // Use the predefined choice.
                val result = nextThreadToSwitch.next()
                check(result in switchableThreads(iThread))
                result
            } else {
                // There is no predefined choice.
                // This can happen if there were forced thread switches after the last predefined one
                // (e.g., thread end, coroutine suspension, acquiring an already acquired lock or monitor.wait).
                // We use a deterministic random here to choose the next thread.
                lastNotInitializedNodeChoices = null // end of execution position choosing initialization because of new switch
                switchableThreads(iThread).random(interleavingFinishingRandom)
            }

        fun isSwitchPosition() = executionPosition in switchPositions
        fun isCrashPosition() = executionPosition in crashPositions
        fun isSystemCrash() = executionPosition !in nonSystemCrashes

        /**
         * Creates a new execution position that corresponds to the current switch/crash point.
         * Unlike switch points, the execution position is just a gradually increasing counter
         * which helps to distinguish different switch points.
         */
        private fun newExecutionPosition(iThread: Int, type: ExplorationNodeType) {
            executionPosition++
            if (type != explorationType) return
            if (executionPosition > lastChosenExecutionPosition()) {
                // Add a new thread choosing node corresponding to the switch at the current execution position.
                lastNotInitializedNodeChoices?.add(Choice(createChildNode(iThread), executionPosition))
            }
        }

        private fun lastChosenExecutionPosition() = max(
            switchPositions.lastOrNull() ?: -1,
            crashPositions.lastOrNull() ?: -1
        )

        private fun createChildNode(iThread: Int): InterleavingTreeNode {
            val moreCrashesPermitted = crashPositions.size < recoverModel.defaultExpectedCrashes()
            return when (explorationType) {
                ExplorationNodeType.SWITCH -> ThreadChoosingNode(switchableThreads(iThread), moreCrashesPermitted)
                ExplorationNodeType.CRASH -> if (moreCrashesPermitted) SwitchOrCrashChoosingNode() else SwitchChoosingNode()
                ExplorationNodeType.NONE -> error("Cannot create child for no exploration node")
            }
        }

        fun newExecutionSwitchPosition(iThread: Int) = newExecutionPosition(iThread, ExplorationNodeType.SWITCH)
        fun newExecutionCrashPosition(iThread: Int) = newExecutionPosition(iThread, ExplorationNodeType.CRASH)
    }

    private inner class InterleavingBuilder {
        private val switchPositions = mutableListOf<Int>()
        private val crashPositions = mutableListOf<Int>()
        private val threadSwitchChoices = mutableListOf<Int>()
        private val nonSystemCrashes = mutableListOf<Int>()
        private var lastNoninitializedNode: InterleavingTreeNode? = null

        val numberOfEvents get() = switchPositions.size + crashPositions.size

        fun addSwitchPosition(switchPosition: Int) {
            switchPositions.add(switchPosition)
        }

        fun addThreadSwitchChoice(iThread: Int) {
            threadSwitchChoices.add(iThread)
        }

        fun addCrashPosition(crashPosition: Int, isSystemCrash: Boolean) {
            crashPositions.add(crashPosition)
            if (!isSystemCrash) nonSystemCrashes.add(crashPosition)
        }

        fun addLastNoninitializedNode(lastNoninitializedNode: InterleavingTreeNode) {
            this.lastNoninitializedNode = lastNoninitializedNode
        }

        fun build() = Interleaving(switchPositions, crashPositions, threadSwitchChoices, nonSystemCrashes, lastNoninitializedNode)
    }

    private enum class ExplorationNodeType {
        SWITCH, CRASH, NONE;

        companion object {
            fun fromNode(node: InterleavingTreeNode?) = when (node) {
                is SwitchChoosingNode -> SWITCH
                is CrashChoosingNode -> CRASH
                else -> NONE
            }
        }
    }
}