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

package org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking

import org.jetbrains.kotlinx.lincheck.annotations.CrashFree
import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.jetbrains.kotlinx.lincheck.nvm.Crash
import org.jetbrains.kotlinx.lincheck.nvm.CrashEnabledVisitor
import org.jetbrains.kotlinx.lincheck.nvm.Probability
import org.jetbrains.kotlinx.lincheck.nvm.RecoverabilityModel
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.verifier.Verifier
import org.objectweb.asm.*
import org.objectweb.asm.commons.GeneratorAdapter
import java.lang.reflect.Method
import kotlin.math.max
import kotlin.reflect.jvm.javaMethod

internal class SwitchesAndCrashesModelCheckingStrategy(
    testCfg: ModelCheckingCTestConfiguration,
    testClass: Class<*>,
    scenario: ExecutionScenario,
    validationFunctions: List<Method>,
    stateRepresentation: Method?,
    verifier: Verifier,
    recoverModel: RecoverabilityModel
) : AbstractModelCheckingStrategy<SwitchesAndCrashesModelCheckingStrategy.SwitchesAndCrashesInterleaving, SwitchesAndCrashesModelCheckingStrategy.SwitchesAndCrashesInterleavingBuilder>(
    testCfg, testClass, scenario,
    validationFunctions, stateRepresentation, verifier, recoverModel
) {
    @Volatile
    private var systemCrashInitiator: Int = NO_CRASH_INITIATOR

    override fun createBuilder() = SwitchesAndCrashesInterleavingBuilder()
    override fun createRoot(): InterleavingTreeNode = ThreadChoosingNodeWithCrashes((0 until nThreads).toList())

    override fun createTransformer(cv: ClassVisitor): ClassVisitor {
        val visitor = CrashEnabledVisitor(cv, testClass, recoverModel.crashes)
        val recoverTransformer = recoverModel.createTransformer(visitor, testClass)
        val managedTransformer = CrashesManagedStrategyTransformer(
            recoverTransformer, tracePointConstructors, testCfg.guarantees, testCfg.eliminateLocalObjects,
            collectStateRepresentation, collectTrace, codeLocationIdProvider, visitor
        )
        return recoverModel.createTransformerWrapper(managedTransformer, testClass)
    }

    override fun initializeInvocation() {
        systemCrashInitiator = NO_CRASH_INITIATOR
        Probability.resetRandom()
        Crash.barrierCallback = { forceSwitchToAwaitSystemCrash() }
        super.initializeInvocation()
    }

    override fun newCrashPoint(iThread: Int) {
        if (!isTestThread(iThread)) return // can crash only test threads
        if (inIgnoredSection(iThread)) return // cannot suspend in ignored sections
        check(iThread == currentThread)
        val isSystemCrash = waitingSystemCrash()
        val shouldCrash = shouldCrash(iThread) || isSystemCrash
        if (shouldCrash) {
            val initializeSystemCrash = !isSystemCrash && isSystemCrash(iThread)
            if (initializeSystemCrash) {
                systemCrashInitiator = iThread
            }
            crashCurrentThread(iThread, isSystemCrash, initializeSystemCrash)
        }
        // continue the operation
    }

    override fun newSwitchPoint(iThread: Int, codeLocation: Int, tracePoint: TracePoint?) {
        if (waitingSystemCrash()) return
        super.newSwitchPoint(iThread, codeLocation, tracePoint)
    }

    private fun onNewCrash(iThread: Int, mustCrash: Boolean) {
        if (mustCrash) {
            currentInterleaving.newExecutionCrashPosition(iThread)
        }
    }

    private fun shouldCrash(iThread: Int): Boolean {
        check(iThread == currentThread)
        currentInterleaving.newExecutionCrashPosition(iThread)
        return currentInterleaving.isCrashPosition()
    }

    private fun isSystemCrash(iThread: Int): Boolean {
        check(iThread == currentThread)
        return currentInterleaving.isSystemCrash()
    }

    private fun waitingSystemCrash() = systemCrashInitiator != NO_CRASH_INITIATOR

    private fun forceSwitchToAwaitSystemCrash() {
        check(waitingSystemCrash())
        val iThread = currentThread
        if (iThread != systemCrashInitiator) {
            currentThread = systemCrashInitiator
            awaitTurn(iThread)
        } else {
            for (t in switchableThreads(iThread)) {
                currentThread = t
                awaitTurn(iThread)
            }
            Crash.onSystemCrash()
            systemCrashInitiator = NO_CRASH_INITIATOR
        }
    }

    private fun crashCurrentThread(iThread: Int, mustCrash: Boolean, initializeSystemCrash: Boolean) {
        val systemCrash = mustCrash || initializeSystemCrash
        val reason = if (systemCrash) CrashReason.SYSTEM_CRASH else CrashReason.CRASH
        traceCollector?.newCrash(iThread, reason)
        onNewCrash(iThread, mustCrash)
        Crash.crash(iThread + 1, null, systemCrash)
    }

    /**
     * Represents a choice of a thread that should be next in the execution.
     */
    private inner class ThreadChoosingNodeWithCrashes(switchableThreads: List<Int>, moreCrashes: Boolean = true) :
        ThreadChoosingNode(switchableThreads) {
        init {
            choices = switchableThreads.map {
                val child =
                    if (recoverModel.crashes && moreCrashes) SwitchOrCrashChoosingNode() else SwitchChoosingNode()
                Choice(child, it)
            }
        }
    }

    private inner class CrashChoosingNode(private val isSystemCrash: Boolean) : InterleavingTreeNode() {
        override fun nextInterleaving(interleavingBuilder: SwitchesAndCrashesInterleavingBuilder): SwitchesAndCrashesInterleaving {
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
                listOf(
                    Choice(SwitchChoosingNode(), 0),
                    Choice(CrashChoosingNode(true), 1),
                    Choice(CrashChoosingNode(false), 2)
                )
            else
                listOf(Choice(SwitchChoosingNode(), 0), Choice(CrashChoosingNode(true), 1))
        }

        override fun nextInterleaving(interleavingBuilder: SwitchesAndCrashesInterleavingBuilder): SwitchesAndCrashesInterleaving {
            val child = chooseUnexploredNode()
            val interleaving = child.node.nextInterleaving(interleavingBuilder)
            updateExplorationStatistics()
            return interleaving
        }
    }


    internal inner class SwitchesAndCrashesInterleaving(
        switchPositions: List<Int>,
        private val crashPositions: List<Int>,
        threadSwitchChoices: List<Int>,
        private val nonSystemCrashes: List<Int>,
        lastNotInitializedNode: InterleavingTreeNode?
    ) : AbstractSwitchesInterleaving(
        switchPositions,
        threadSwitchChoices,
        lastNotInitializedNode
    ) {
        private lateinit var explorationType: ExplorationNodeType

        override fun initialize() {
            explorationType = ExplorationNodeType.fromNode(lastNotInitializedNode)
            super.initialize()
        }

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

        private fun lastChosenExecutionPosition() =
            max(switchPositions.lastOrNull() ?: -1, crashPositions.lastOrNull() ?: -1)

        private fun createChildNode(iThread: Int): InterleavingTreeNode {
            val moreCrashesPermitted = crashPositions.size < recoverModel.defaultExpectedCrashes()
            return when (explorationType) {
                ExplorationNodeType.SWITCH -> ThreadChoosingNodeWithCrashes(switchableThreads(iThread))
                ExplorationNodeType.CRASH -> if (moreCrashesPermitted) SwitchOrCrashChoosingNode() else SwitchChoosingNode()
                ExplorationNodeType.NONE -> error("Cannot create child for no exploration node")
            }
        }

        override fun newExecutionPosition(iThread: Int) = newExecutionPosition(iThread, ExplorationNodeType.SWITCH)
        fun newExecutionCrashPosition(iThread: Int) = newExecutionPosition(iThread, ExplorationNodeType.CRASH)
    }


    internal inner class SwitchesAndCrashesInterleavingBuilder : AbstractSwitchesInterleavingBuilder() {
        private val crashPositions = mutableListOf<Int>()
        private val nonSystemCrashes = mutableListOf<Int>()
        override val numberOfEvents get() = switchPositions.size + crashPositions.size

        fun addCrashPosition(crashPosition: Int, isSystemCrash: Boolean) {
            crashPositions.add(crashPosition)
            if (!isSystemCrash) nonSystemCrashes.add(crashPosition)
        }

        override fun build() = SwitchesAndCrashesInterleaving(
            switchPositions,
            crashPositions,
            threadSwitchChoices,
            nonSystemCrashes,
            lastNoninitializedNode
        )
    }

    private enum class ExplorationNodeType {
        SWITCH, CRASH, NONE;

        companion object {
            fun fromNode(node: AbstractModelCheckingStrategy<*, *>.InterleavingTreeNode?) = when (node) {
                is SwitchChoosingNode -> SWITCH
                is CrashChoosingNode -> CRASH
                else -> NONE
            }
        }
    }
}

private class CrashesManagedStrategyTransformer(
    cv: ClassVisitor?,
    tracePointConstructors: MutableList<TracePointConstructor>,
    guarantees: List<ManagedStrategyGuarantee>,
    eliminateLocalObjects: Boolean,
    collectStateRepresentation: Boolean,
    constructTraceRepresentation: Boolean,
    codeLocationIdProvider: CodeLocationIdProvider,
    val crashesEnabledVisitor: CrashEnabledVisitor,
) : ManagedStrategyTransformer(
    cv, tracePointConstructors, guarantees, eliminateLocalObjects,
    collectStateRepresentation, constructTraceRepresentation, codeLocationIdProvider
) {
    override fun createTransformerBeforeSharedVariableVisitor(
        mv: MethodVisitor?,
        methodName: String,
        access: Int,
        desc: String
    ): MethodVisitor? = if (crashesEnabledVisitor.shouldTransform) {
        CrashManagedTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
    } else mv

    private inner class CrashManagedTransformer(methodName: String, mv: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, mv) {
        private var shouldTransform = methodName != "<clinit>" && (mv.access and Opcodes.ACC_BRIDGE) == 0
        private var superConstructorCalled = methodName != "<init>"

        override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
            if (descriptor == CRASH_FREE_TYPE) {
                shouldTransform = false
            }
            return adapter.visitAnnotation(descriptor, visible)
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean
        ) {
            if (!superConstructorCalled && opcode == Opcodes.INVOKESPECIAL) {
                superConstructorCalled = true
            }
            if (owner !== null && owner.startsWith("org/jetbrains/kotlinx/lincheck/nvm/api/")) {
                // Here the order of points is crucial - switch point must be before crash point.
                // The use case is the following: thread switches on the switch point,
                // then another thread initiates a system crash, force switches to the first thread
                // which crashes immediately.
                invokeBeforeNVMOperation()
                if (name !== null && name.toLowerCase().contains("flush")) invokeBeforeCrashPoint()
            }
            adapter.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }

        override fun visitInsn(opcode: Int) {
            if (opcode in returnInstructions) invokeBeforeCrashPoint()
            adapter.visitInsn(opcode)
        }

        private fun invokeBeforeCrashPoint() {
            if (!shouldTransform || !superConstructorCalled) return
            loadStrategy()
            loadCurrentThreadNumber()
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, BEFORE_CRASH_METHOD)
        }

        private fun invokeBeforeNVMOperation() {
            if (!shouldTransform || !superConstructorCalled) return
            loadStrategy()
            loadCurrentThreadNumber()
            val tracePointLocal = newTracePointLocal()
            loadNewCodeLocationAndTracePoint(
                tracePointLocal,
                METHOD_TRACE_POINT_TYPE
            ) { iThread, actorId, callStackTrace, ste ->
                MethodCallTracePoint(iThread, actorId, callStackTrace, methodName, ste)
            }
            adapter.invokeVirtual(MANAGED_STRATEGY_TYPE, BEFORE_NVM_OPERATION_METHOD)
        }
    }
}

private val CRASH_FREE_TYPE = Type.getDescriptor(CrashFree::class.java)
private val BEFORE_CRASH_METHOD =
    org.objectweb.asm.commons.Method.getMethod(ManagedStrategy::beforeCrashPoint.javaMethod)
private val BEFORE_NVM_OPERATION_METHOD =
    org.objectweb.asm.commons.Method.getMethod(ManagedStrategy::beforeNVMOperation.javaMethod)
private val MANAGED_STRATEGY_TYPE = Type.getType(ManagedStrategy::class.java)
private val METHOD_TRACE_POINT_TYPE = Type.getType(MethodCallTracePoint::class.java)

private val returnInstructions = listOf(
    Opcodes.RETURN,
    Opcodes.ARETURN,
    Opcodes.DRETURN,
    Opcodes.FRETURN,
    Opcodes.IRETURN,
    Opcodes.LRETURN
)

private const val NO_CRASH_INITIATOR = -1
