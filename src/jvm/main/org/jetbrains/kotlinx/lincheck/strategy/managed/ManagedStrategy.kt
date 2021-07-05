/*
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
package org.jetbrains.kotlinx.lincheck.strategy.managed

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.*
import org.jetbrains.kotlinx.lincheck.CancellationResult.*
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.nvm.RecoverabilityModel
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.objectweb.asm.*
import java.io.*
import java.lang.reflect.*
import java.util.*
import kotlin.collections.set

/**
 * This is an abstraction for all managed strategies, which encapsulated
 * the required byte-code transformation and [running][Runner] logic and provides
 * a high-level level interface to implement the strategy logic.
 *
 * It is worth noting that here we also solve all the transformation
 * and class loading problems.
 */
abstract class ManagedStrategy(
    protected val testClass: Class<*>,
    scenario: ExecutionScenario,
    private val verifier: Verifier,
    protected val validationFunctions: List<Method>,
    private val stateRepresentationFunction: Method?,
    protected val testCfg: ManagedCTestConfiguration,
    protected val recoverModel: RecoverabilityModel
) : Strategy(scenario), Closeable {
    // The number of parallel threads.
    protected val nThreads: Int = scenario.parallelExecution.size
    // Runner for scenario invocations,
    // can be replaced with a new one for trace construction.
    private var runner: Runner
    // Shares location ids between class transformers in order
    // to keep them different in different code locations.
    internal val codeLocationIdProvider = CodeLocationIdProvider()

    // == EXECUTION CONTROL FIELDS ==

    // Which thread is allowed to perform operations?
    @Volatile
    protected var currentThread: Int = 0
    // Which threads finished all the operations?
    private val finished = BooleanArray(nThreads) { false }
    // Which threads are suspended?
    private val isSuspended = BooleanArray(nThreads) { false }
    // Current actor id for each thread.
    protected val currentActorId = IntArray(nThreads)
    // Ihe number of entered but not left (yet) blocks that should be ignored by the strategy analysis for each thread.
    private val ignoredSectionDepth = IntArray(nThreads) { 0 }
    // Detector of loops or hangs (i.e. active locks).
    private lateinit var loopDetector: LoopDetector
    // Tracker of acquisitions and releases of monitors.
    private lateinit var monitorTracker: MonitorTracker

    // InvocationResult that was observed by the strategy during the execution (e.g., a deadlock).
    @Volatile
    protected var suddenInvocationResult: InvocationResult? = null

    // == TRACE CONSTRUCTION FIELDS ==

    // Whether an additional information requires for the trace construction should be collected.
    protected var collectTrace = false
    // Whether state representations (see `@StateRepresentation`) should be collected after interleaving events.
    protected val collectStateRepresentation get() = collectTrace && stateRepresentationFunction != null
    // Trace point constructors, where `tracePointConstructors[id]`
    // stores a constructor for the corresponding code location.
    internal val tracePointConstructors: MutableList<TracePointConstructor> = ArrayList()
    // Collector of all events in the execution such as thread switches.
    internal var traceCollector: TraceCollector? = null // null when `collectTrace` is false
        private set
    // Stores the currently execuring methods call stack for each thread.
    private val callStackTrace = Array(nThreads) { mutableListOf<CallStackTraceElement>() }
    // Stores the global number of method calls.
    private var methodCallNumber = 0
    // In case of suspension, the call stack of the corresponding `suspend`
    // methods is stored here, so that the same method call identifiers are
    // used on resumption, and the trace point before and after the suspension
    // correspond to the same method call in the trace.
    private val suspendedFunctionsStack = Array(nThreads) { mutableListOf<Int>() }

    init {
        runner = createRunner()
        // The managed state should be initialized before еру test class transformation.
        try {
            initializeManagedState()
            runner.initialize()
        } catch (t: Throwable) {
            runner.close()
            throw t
        }
    }

    private fun createRunner(): Runner =
        ManagedStrategyRunner(this, testClass, validationFunctions, stateRepresentationFunction, testCfg.timeoutMs, UseClocks.ALWAYS, recoverModel)

    private fun initializeManagedState() {
        ManagedStrategyStateHolder.setState(runner.classLoader, this, testClass)
    }

    override fun createTransformer(cv: ClassVisitor): ClassVisitor = ManagedStrategyTransformer(
        cv = cv,
        tracePointConstructors = tracePointConstructors,
        guarantees = testCfg.guarantees,
        eliminateLocalObjects = testCfg.eliminateLocalObjects,
        collectStateRepresentation = collectStateRepresentation,
        constructTraceRepresentation = collectTrace,
        codeLocationIdProvider = codeLocationIdProvider
    )

    override fun needsTransformation(): Boolean = true

    override fun run(): LincheckFailure? = runImpl().also { close() }

    // == STRATEGY INTERFACE METHODS ==

    /**
     * This method implements the strategy logic.
     */
    protected abstract fun runImpl(): LincheckFailure?

    /**
     * This method is invoked before every thread context switch.
     * @param iThread current thread that is about to be switched
     * @param mustSwitch whether the switch is not caused by strategy and is a must-do (e.g, because of monitor wait)
     */
    protected open fun onNewSwitch(iThread: Int, mustSwitch: Boolean) {}

    /**
     * Returns whether thread should switch at the switch point.
     * @param iThread the current thread
     */
    protected abstract fun shouldSwitch(iThread: Int): Boolean

    /**
     * Choose a thread to switch from thread [iThread].
     * @return id the chosen thread
     */
    protected abstract fun chooseThread(iThread: Int): Int

    /**
     * Returns all data to the initial state.
     */
    protected open fun initializeInvocation() {
        finished.fill(false)
        isSuspended.fill(false)
        currentActorId.fill(-1)
        loopDetector = LoopDetector(testCfg.hangingDetectionThreshold)
        monitorTracker = MonitorTracker(nThreads)
        traceCollector = if (collectTrace) TraceCollector() else null
        suddenInvocationResult = null
        ignoredSectionDepth.fill(0)
        callStackTrace.forEach { it.clear() }
        suspendedFunctionsStack.forEach { it.clear() }
        ManagedStrategyStateHolder.resetState(runner.classLoader, testClass)
    }

    // == BASIC STRATEGY METHODS ==

    /**
     * Checks whether the [result] is a failing one or is [CompletedInvocationResult]
     * but the verification fails, and return the corresponding failure.
     * Returns `null` if the result is correct.
     */
    protected fun checkResult(result: InvocationResult): LincheckFailure? = when (result) {
        is CompletedInvocationResult -> {
            if (verifier.verifyResults(scenario, result.results)) null
            else IncorrectResultsFailure(scenario, result.results.withoutCrashes, collectTrace(result))
        }
        else -> result.toLincheckFailure(scenario, collectTrace(result))
    }

    /**
     * Re-runs the last invocation to collect its trace.
     */
    private fun collectTrace(failingResult: InvocationResult): Trace? {
        val detectedByStrategy = suddenInvocationResult != null
        val canCollectTrace = when {
            detectedByStrategy -> true // ObstructionFreedomViolationInvocationResult or UnexpectedExceptionInvocationResult
            failingResult is CompletedInvocationResult -> true
            failingResult is ValidationFailureInvocationResult -> true
            else -> false
        }

        if (!canCollectTrace) {
            // Interleaving events can be collected almost always,
            // except for the strange cases such as Runner's timeout or exceptions in LinCheck.
            return null
        }
        // Re-transform class constructing trace
        collectTrace = true
        // Replace the current runner with a new one in order to use a new
        // `TransformationClassLoader` with a transformer that inserts the trace collection logic.
        runner.close()
        runner = createRunner()
        initializeManagedState()
        runner.initialize()
        val loggedResults = runInvocation()
        val sameResultTypes = loggedResults.javaClass == failingResult.javaClass
        val sameResults = loggedResults !is CompletedInvocationResult || failingResult !is CompletedInvocationResult || loggedResults.results == failingResult.results
        check(sameResultTypes && sameResults) {
            StringBuilder().apply {
                appendln("Non-determinism found. Probably caused by non-deterministic code (WeakHashMap, Object.hashCode, etc).")
                appendln("== Reporting the first execution without execution trace ==")
                appendln(failingResult.asLincheckFailureWithoutTrace())
                appendln("== Reporting the second execution ==")
                appendln(loggedResults.toLincheckFailure(scenario, Trace(traceCollector!!.trace, testCfg.verboseTrace)).toString())
            }.toString()
        }
        return Trace(traceCollector!!.trace, testCfg.verboseTrace)
    }

    /**
     * Runs the next invocation with the same [scenario][ExecutionScenario].
     */
    protected fun runInvocation(): InvocationResult {
        initializeInvocation()
        val result = runner.run()
        // Has strategy already determined the invocation result?
        suddenInvocationResult?.let { return it  }
        return result
    }

    private fun failIfObstructionFreedomIsRequired(lazyMessage: () -> String) {
        if (testCfg.checkObstructionFreedom && !curActorIsBlocking && !concurrentActorCausesBlocking) {
            suddenInvocationResult = ObstructionFreedomViolationInvocationResult(lazyMessage())
            // Forcibly finish the current execution by throwing an exception.
            throw ForcibleExecutionFinishException
        }
    }

    private val curActorIsBlocking: Boolean
        get() = scenario.parallelExecution[currentThread][currentActorId[currentThread]].blocking

    private val concurrentActorCausesBlocking: Boolean
        get() = currentActorId.mapIndexed { iThread, actorId ->
                    if (iThread != currentThread && !finished[iThread])
                        scenario.parallelExecution[iThread][actorId]
                    else null
                }.filterNotNull().any { it.causesBlocking }

    private fun checkLiveLockHappened(interleavingEventsCount: Int) {
        if (interleavingEventsCount > ManagedCTestConfiguration.LIVELOCK_EVENTS_THRESHOLD) {
            suddenInvocationResult = DeadlockInvocationResult(collectThreadDump(runner))
            // Forcibly finish the current execution by throwing an exception.
            throw ForcibleExecutionFinishException
        }
    }

    override fun close() {
        runner.close()
    }

    // == EXECUTION CONTROL METHODS ==

    /**
     * Create a new switch point, where a thread context switch can occur.
     * @param iThread the current thread
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of the point in code.
     */
    protected open fun newSwitchPoint(iThread: Int, codeLocation: Int, tracePoint: TracePoint?) {
        if (!isTestThread(iThread)) return // can switch only test threads
        if (inIgnoredSection(iThread)) return // cannot suspend in ignored sections
        check(iThread == currentThread)
        var isLoop = false
        if (loopDetector.visitCodeLocation(iThread, codeLocation)) {
            failIfObstructionFreedomIsRequired {
                // Log the last event that caused obstruction freedom violation
                traceCollector?.passCodeLocation(tracePoint)
                "Obstruction-freedom is required but an active lock has been found"
            }
            checkLiveLockHappened(loopDetector.totalOperations)
            isLoop = true
        }
        val shouldSwitch = shouldSwitch(iThread) or isLoop
        if (shouldSwitch) {
            val reason = if (isLoop) SwitchReason.ACTIVE_LOCK else SwitchReason.STRATEGY_SWITCH
            switchCurrentThread(iThread, reason)
        }
        traceCollector?.passCodeLocation(tracePoint)
        // continue the operation
    }

    /**
     * This method is executed as the first thread action.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     */
    open fun onStart(iThread: Int) {
        awaitTurn(iThread)
    }

    /**
     * This method is executed as the last thread action if no exception has been thrown.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     */
    open fun onFinish(iThread: Int) {
        awaitTurn(iThread)
        finished[iThread] = true
        traceCollector?.finishThread(iThread)
        doSwitchCurrentThread(iThread, true)
    }

    /**
     * This method is executed if an exception has been thrown.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param exception the exception that was thrown
     */
    open fun onFailure(iThread: Int, exception: Throwable) {
        // Despite the fact that the corresponding failure will be detected by the runner,
        // the managed strategy can construct a trace to reproduce this failure.
        // Let's then store the corresponding failing result and construct the trace.
        if (exception === ForcibleExecutionFinishException) return // not a forcible execution finish
        suddenInvocationResult = UnexpectedExceptionInvocationResult(wrapInvalidAccessFromUnnamedModuleExceptionWithDescription(exception))
    }

    override fun onActorStart(iThread: Int) {
        currentActorId[iThread]++
        callStackTrace[iThread].clear()
        suspendedFunctionsStack[iThread].clear()
        loopDetector.reset(iThread)
    }

    /**
     * Returns whether the specified thread is active and
     * can continue its execution (i.e. is not blocked/finished).
     */
    private fun isActive(iThread: Int): Boolean =
        !finished[iThread] &&
        !monitorTracker.isWaiting(iThread) &&
        !(isSuspended[iThread] && !runner.isCoroutineResumed(iThread, currentActorId[iThread]))

    /**
     * Waits until the specified thread can continue
     * the execution according to the strategy decision.
     */
    protected fun awaitTurn(iThread: Int) {
        // Wait actively until the thread is allowed to continue
        while (currentThread != iThread) {
            // Finish forcibly if an error occurred and we already have an `InvocationResult`.
            if (suddenInvocationResult != null) throw ForcibleExecutionFinishException
            Thread.yield()
        }
    }

    /**
     * A regular context thread switch to another thread.
     */
    private fun switchCurrentThread(iThread: Int, reason: SwitchReason = SwitchReason.STRATEGY_SWITCH, mustSwitch: Boolean = false) {
        traceCollector?.newSwitch(iThread, reason)
        doSwitchCurrentThread(iThread, mustSwitch)
        awaitTurn(iThread)
    }

    private fun doSwitchCurrentThread(iThread: Int, mustSwitch: Boolean = false) {
        onNewSwitch(iThread, mustSwitch)
        val switchableThreads = switchableThreads(iThread)
        if (switchableThreads.isEmpty()) {
            if (mustSwitch && !finished.all { it }) {
                // all threads are suspended
                // then switch on any suspended thread to finish it and get SuspendedResult
                val nextThread = (0 until nThreads).firstOrNull { !finished[it] && isSuspended[it] }
                if (nextThread == null) {
                    // must switch not to get into a deadlock, but there are no threads to switch.
                    suddenInvocationResult = DeadlockInvocationResult(collectThreadDump(runner))
                    // forcibly finish execution by throwing an exception.
                    throw ForcibleExecutionFinishException
                }
                currentThread = nextThread
            }
            return // ignore switch, because there is no one to switch to
        }
        val nextThreadId = chooseThread(iThread)
        currentThread = nextThreadId
    }

    /**
     * Threads to which an execution can be switched from thread [iThread].
     */
    protected fun switchableThreads(iThread: Int) = (0 until nThreads).filter { it != iThread && isActive(it) }

    protected fun isTestThread(iThread: Int) = iThread < nThreads

    /**
     * The execution in an ignored section (added by transformer) or not in a test thread must not add switch points.
     * Additionally, after [ForcibleExecutionFinishException] everything is ignored.
     */
    protected fun inIgnoredSection(iThread: Int): Boolean =
        !isTestThread(iThread) || ignoredSectionDepth[iThread] > 0 || suddenInvocationResult != null

    // == LISTENING METHODS ==

    /**
     * This method is executed before a shared variable read operation.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    internal fun beforeSharedVariableRead(iThread: Int, codeLocation: Int, tracePoint: ReadTracePoint?) {
        newSwitchPoint(iThread, codeLocation, tracePoint)
    }

    /**
     * This method is executed before a shared variable write operation.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    internal fun beforeSharedVariableWrite(iThread: Int, codeLocation: Int, tracePoint: WriteTracePoint?) {
        newSwitchPoint(iThread, codeLocation, tracePoint)
    }

    /**
     * This method is executed before an atomic method call.
     * Atomic method is a method that is marked by ManagedGuarantee to be treated as atomic.
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    internal fun beforeAtomicMethodCall(iThread: Int, codeLocation: Int) {
        if (!isTestThread(iThread)) return
        // re-use last call trace point
        newSwitchPoint(iThread, codeLocation, callStackTrace[iThread].lastOrNull()?.call)
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @return whether lock should be actually acquired
     */
    internal fun beforeLockAcquire(iThread: Int, codeLocation: Int, tracePoint: MonitorEnterTracePoint?, monitor: Any): Boolean {
        if (!isTestThread(iThread)) return true
        newSwitchPoint(iThread, codeLocation, tracePoint)
        // Try to acquire the monitor
        if (!monitorTracker.acquireMonitor(iThread, monitor)) {
            failIfObstructionFreedomIsRequired { "Obstruction-freedom is required but a lock has been found" }
            // Switch to another thread and wait for a moment when the monitor can be acquired
            switchCurrentThread(iThread, SwitchReason.LOCK_WAIT, true)
            // Now it is possible to acquire the monitor, do it then.
            require(monitorTracker.acquireMonitor(iThread, monitor))
        }
        // The monitor is acquired, finish.
        return false
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @return whether lock should be actually released
     */
    internal fun beforeLockRelease(iThread: Int, codeLocation: Int, tracePoint: MonitorExitTracePoint?, monitor: Any): Boolean {
        if (!isTestThread(iThread)) return true
        monitorTracker.releaseMonitor(monitor)
        traceCollector?.passCodeLocation(tracePoint)
        return false
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @param withTimeout `true` if is invoked with timeout, `false` otherwise.
     * @return whether park should be executed
     */
    @Suppress("UNUSED_PARAMETER")
    internal fun beforePark(iThread: Int, codeLocation: Int, tracePoint: ParkTracePoint?, withTimeout: Boolean): Boolean {
        newSwitchPoint(iThread, codeLocation, tracePoint)
        return false
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     */
    @Suppress("UNUSED_PARAMETER")
    internal fun afterUnpark(iThread: Int, codeLocation: Int, tracePoint: UnparkTracePoint?, thread: Any) {
        if (!isTestThread(iThread)) return
        traceCollector?.passCodeLocation(tracePoint)
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @param withTimeout `true` if is invoked with timeout, `false` otherwise.
     * @return whether `Object.wait` should be executed
     */
    internal fun beforeWait(iThread: Int, codeLocation: Int, tracePoint: WaitTracePoint?, monitor: Any, withTimeout: Boolean): Boolean {
        if (!isTestThread(iThread)) return true
        newSwitchPoint(iThread, codeLocation, tracePoint)
        failIfObstructionFreedomIsRequired { "Obstruction-freedom is required but a waiting on a monitor block has been found" }
        if (withTimeout) return false // timeouts occur instantly
        monitorTracker.waitOnMonitor(iThread, monitor)
        // switch to another thread and wait till a notify event happens
        switchCurrentThread(iThread, SwitchReason.MONITOR_WAIT, true)
        require(monitorTracker.acquireMonitor(iThread, monitor)) // acquire the lock again
        return false
    }

    /**
     * @param iThread the number of the executed thread according to the [scenario][ExecutionScenario].
     * @param codeLocation the byte-code location identifier of this operation.
     * @return whether `Object.notify` should be executed
     */
    internal fun beforeNotify(iThread: Int, codeLocation: Int, tracePoint: NotifyTracePoint?, monitor: Any, notifyAll: Boolean): Boolean {
        if (!isTestThread(iThread)) return true
        if (notifyAll)
            monitorTracker.notifyAll(monitor)
        else
            monitorTracker.notify(monitor)
        traceCollector?.passCodeLocation(tracePoint)
        return false
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was suspended.
     * @param iThread number of invoking thread
     */
    internal fun afterCoroutineSuspended(iThread: Int) {
        check(currentThread == iThread)
        isSuspended[iThread] = true
        if (runner.isCoroutineResumed(iThread, currentActorId[iThread])) {
            // `COROUTINE_SUSPENSION_CODE_LOCATION`, because we do not know the actual code location
            newSwitchPoint(iThread, COROUTINE_SUSPENSION_CODE_LOCATION, null)
        } else {
            // coroutine suspension does not violate obstruction-freedom
            switchCurrentThread(iThread, SwitchReason.SUSPENDED, true)
        }
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was resumed.
     * @param iThread number of invoking thread
     */
    internal fun afterCoroutineResumed(iThread: Int) {
        check(currentThread == iThread)
        isSuspended[iThread] = false
    }

    /**
     * This method is invoked by a test thread
     * if a coroutine was cancelled.
     * @param iThread number of invoking thread
     */
    internal fun afterCoroutineCancelled(iThread: Int) {
        check(currentThread == iThread)
        isSuspended[iThread] = false
        // method will not be resumed after suspension, so clear prepared for resume call stack
        suspendedFunctionsStack[iThread].clear()
    }

    /**
     * This method is invoked by a test thread
     * before each ignored section start.
     * These sections are determined by Strategy.ignoredEntryPoints()
     * @param iThread number of invoking thread
     */
    internal fun enterIgnoredSection(iThread: Int) {
        if (isTestThread(iThread))
            ignoredSectionDepth[iThread]++
    }

    /**
     * This method is invoked by a test thread
     * after each ignored section end.
     * @param iThread number of invoking thread
     */
    internal fun leaveIgnoredSection(iThread: Int) {
        if (isTestThread(iThread))
            ignoredSectionDepth[iThread]--
    }

    /**
     * This method is invoked by a test thread
     * before each method invocation.
     * @param codeLocation the byte-code location identifier of this invocation
     * @param iThread number of invoking thread
     */
    @Suppress("UNUSED_PARAMETER")
    internal fun beforeMethodCall(iThread: Int, codeLocation: Int, tracePoint: MethodCallTracePoint) {
        if (isTestThread(iThread) && !inIgnoredSection(iThread)) {
            check(collectTrace) { "This method should be called only when logging is enabled" }
            val callStackTrace = callStackTrace[iThread]
            val suspendedMethodStack = suspendedFunctionsStack[iThread]
            val methodId = if (suspendedMethodStack.isNotEmpty()) {
                // if there was a suspension before, then instead of creating a new identifier
                // use the one that the suspended call had
                val lastId = suspendedMethodStack.last()
                suspendedMethodStack.removeAt(suspendedMethodStack.lastIndex)
                lastId
            } else {
                methodCallNumber++
            }
            // code location of the new method call is currently the last
            callStackTrace.add(CallStackTraceElement(tracePoint, methodId))
        }
    }

    /**
     * This method is invoked by a test thread
     * after each method invocation.
     * @param iThread number of invoking thread
     * @param tracePoint the corresponding trace point for the invocation
     */
    internal fun afterMethodCall(iThread: Int, tracePoint: MethodCallTracePoint) {
        if (isTestThread(iThread) && !inIgnoredSection(iThread)) {
            check(collectTrace) { "This method should be called only when logging is enabled" }
            val callStackTrace = callStackTrace[iThread]
            if (tracePoint.wasSuspended) {
                // if a method call is suspended, save its identifier to reuse for continuation resuming
                suspendedFunctionsStack[iThread].add(callStackTrace.last().identifier)
            }
            callStackTrace.removeAt(callStackTrace.lastIndex)
        }
    }

    // == LOGGING METHODS ==

    /**
     * Creates a new [TracePoint] for a visited code location.
     * The type of the code location is defined by the used constructor.
     * This method's invocations are inserted by transformer at each code location.
     * @param constructorId which constructor to use for creating code location
     * @return the created interleaving point
     */
    fun createTracePoint(constructorId: Int): TracePoint = doCreateTracePoint(tracePointConstructors[constructorId])

    /**
     * Creates a new [CoroutineCancellationTracePoint].
     * This method is similar to [createTracePoint] method, but also adds the new trace point to the trace.
     */
    internal fun createAndLogCancellationTracePoint(): CoroutineCancellationTracePoint? {
        if (collectTrace) {
            val cancellationTracePoint = doCreateTracePoint(::CoroutineCancellationTracePoint)
            traceCollector?.passCodeLocation(cancellationTracePoint)
            return cancellationTracePoint
        }
        return null
    }

    private fun <T : TracePoint> doCreateTracePoint(constructor: (iThread: Int, actorId: Int, CallStackTrace) -> T): T {
        val iThread = currentThreadNumber()
        // use any actor id for non-test threads
        val actorId = if (!isTestThread(iThread)) Int.MIN_VALUE else currentActorId[iThread]
        return constructor(iThread, actorId, callStackTrace.getOrNull(iThread)?.toList() ?: emptyList())
    }

    /**
     * Creates a state representation and logs it.
     * This method invocations are inserted by transformer
     * after each write operation and atomic method invocation.
     */
    fun addStateRepresentation(iThread: Int) {
        if (!inIgnoredSection(iThread)) {
            check(collectTrace) { "This method should be called only when logging is enabled" }
            traceCollector?.addStateRepresentation(iThread)
        }
    }

    // == UTILITY METHODS ==

    /**
     * This method is invoked by transformed via [ManagedStrategyTransformer] code,
     * it helps to determine the number of thread we are executing on.
     *
     * @return the number of the current thread according to the [execution scenario][ExecutionScenario].
     */
    fun currentThreadNumber(): Int {
        val t = Thread.currentThread()
        return if (t is FixedActiveThreadsExecutor.TestThread) {
            t.iThread
        } else {
            nThreads
        }
    }

    private fun InvocationResult.asLincheckFailureWithoutTrace(): LincheckFailure {
        if (this is CompletedInvocationResult)
            return IncorrectResultsFailure(scenario, results, null)
        return toLincheckFailure(scenario, null)
    }

    /**
     * Logs thread events such as thread switches and passed code locations.
     */
    internal inner class TraceCollector {
        private val _trace = mutableListOf<TracePoint>()
        val trace: List<TracePoint> = _trace

        fun newSwitch(iThread: Int, reason: SwitchReason) {
            _trace += SwitchEventTracePoint(iThread, currentActorId[iThread], reason, callStackTrace[iThread].toList())
        }

        fun newCrash(iThread: Int, reason: CrashReason) {
            _trace += CrashTracePoint(iThread, currentActorId[iThread], callStackTrace[iThread].toList(), reason)
        }

        fun finishThread(iThread: Int) {
            _trace += FinishThreadTracePoint(iThread)
        }

        fun passCodeLocation(tracePoint: TracePoint?) {
            _trace += tracePoint!!
        }

        fun addStateRepresentation(iThread: Int) {
            val stateRepresentation = runner.constructStateRepresentation()!!
            // use call stack trace of the previous trace point
            val callStackTrace = _trace.last().callStackTrace.toList()
            _trace += StateRepresentationTracePoint(iThread, currentActorId[iThread], stateRepresentation, callStackTrace)
        }
    }


    // == NVM related ==

    internal fun beforeCrashPoint(iThread: Int) = newCrashPoint(iThread)
    internal fun beforeNVMOperation(iThread: Int, codeLocation: Int, tracePoint: MethodCallTracePoint?) =
        newSwitchPoint(iThread, codeLocation, tracePoint)

    protected open fun newCrashPoint(iThread: Int) {}

    // == NVM related ==
}

/**
 * This class is a [ParallelThreadsRunner] with some overrides that add callbacks
 * to the strategy so that it can known about some required events.
 */
internal class ManagedStrategyRunner(
    private val managedStrategy: ManagedStrategy, testClass: Class<*>, validationFunctions: List<Method>,
    stateRepresentationMethod: Method?, timeoutMs: Long, useClocks: UseClocks, recoverModel: RecoverabilityModel
) : ParallelThreadsRunner(managedStrategy, testClass, validationFunctions, stateRepresentationMethod, timeoutMs, useClocks, recoverModel) {
    override fun onStart(iThread: Int) {
        super.onStart(iThread)
        managedStrategy.onStart(iThread)
    }

    override fun onFinish(iThread: Int) {
        super.onFinish(iThread)
        managedStrategy.onFinish(iThread)
    }

    override fun onFailure(iThread: Int, e: Throwable) {
        managedStrategy.onFailure(iThread, e)
        super.onFailure(iThread, e)
    }

    override fun afterCoroutineSuspended(iThread: Int) {
        super.afterCoroutineSuspended(iThread)
        managedStrategy.afterCoroutineSuspended(iThread)
    }

    override fun afterCoroutineResumed(iThread: Int) {
        super.afterCoroutineResumed(iThread)
        managedStrategy.afterCoroutineResumed(iThread)
    }

    override fun afterCoroutineCancelled(iThread: Int) {
        super.afterCoroutineCancelled(iThread)
        managedStrategy.afterCoroutineCancelled(iThread)
    }

    override fun constructStateRepresentation(): String? {
        // Enter ignored section, because Runner will call transformed state representation method
        val iThread = managedStrategy.currentThreadNumber()
        managedStrategy.enterIgnoredSection(iThread)
        val stateRepresentation = super.constructStateRepresentation()
        managedStrategy.leaveIgnoredSection(iThread)
        return stateRepresentation
    }

    override fun <T> cancelByLincheck(cont: CancellableContinuation<T>, promptCancellation: Boolean): CancellationResult {
        // Create a cancellation trace point before `cancel`, so that cancellation trace point
        // precede the events in `onCancellation` handler.
        val cancellationTracePoint = managedStrategy.createAndLogCancellationTracePoint()
        try {
            // Call the `cancel` method.
            val cancellationResult = super.cancelByLincheck(cont, promptCancellation)
            // Pass the result to `cancellationTracePoint`.
            cancellationTracePoint?.initializeCancellationResult(cancellationResult)
            // Invoke `strategy.afterCoroutineCancelled` if the coroutine was cancelled successfully.
            if (cancellationResult != CANCELLATION_FAILED)
                managedStrategy.afterCoroutineCancelled(managedStrategy.currentThreadNumber())
            return cancellationResult
        } catch(e: Throwable) {
            cancellationTracePoint?.initializeException(e)
            throw e // throw further
        }
    }
}

/**
 * Detects loops, active locks and live locks when the same code location is visited too often.
 */
private class LoopDetector(private val hangingDetectionThreshold: Int) {
    private var lastIThread = -1 // no last thread
    private val operationCounts = mutableMapOf<Int, Int>()
    var totalOperations = 0
        private set

    /**
     * Returns `true` if a loop or a hang is detected,
     * `false` otherwise.
     */
    fun visitCodeLocation(iThread: Int, codeLocation: Int): Boolean {
        // Increase the total number of happened operations for live-lock detection
        totalOperations++
        // Have the thread changed? Reset the counters in this case.
        if (lastIThread != iThread) reset(iThread)
        // Ignore coroutine suspension code locations.
        if (codeLocation == COROUTINE_SUSPENSION_CODE_LOCATION) return false
        // Increment the number of times the specified code location is visited.
        val count = operationCounts.getOrDefault(codeLocation, 0) + 1
        operationCounts[codeLocation] = count
        // Check whether the count exceeds the maximum number of repetitions for loop/hang detection.
        return count > hangingDetectionThreshold
    }

    /**
     * Resets the counters for the specified thread.
     */
    fun reset(iThread: Int) {
        operationCounts.clear()
        lastIThread = iThread
    }
}

/**
 * Tracks synchronization operations with monitors (acquire/release, wait/notify) to maintain a set of active threads.
 */
private class MonitorTracker(nThreads: Int) {
    // Maintains a set of acquired monitors with an information on which thread
    // performed the acquisition and the the reentrancy depth.
    private val acquiredMonitors = IdentityHashMap<Any, MonitorAcquiringInfo>()
    // Maintains a set of monitors on which each thread is waiting.
    // Note, that a thread can wait on a free monitor if it is waiting for
    // a `notify` call.
    // Stores `null` if thread is not waiting on any monitor.
    private val acquiringMonitors = Array<Any?>(nThreads) { null }
    // Stores `true` for the threads which are waiting for a
    // `notify` call on the monitor stored in `acquiringMonitor`.
    private val waitForNotify = BooleanArray(nThreads) { false }

    /**
     * Performs a logical acquisition.
     */
    fun acquireMonitor(iThread: Int, monitor: Any): Boolean {
        // Increment the reentrant depth and store the
        // acquisition info if needed.
        val ai = acquiredMonitors.computeIfAbsent(monitor) { MonitorAcquiringInfo(iThread, 0) }
        if (ai.iThread != iThread) {
            acquiringMonitors[iThread] = monitor
            return false
        }
        ai.timesAcquired++
        acquiringMonitors[iThread] = null // re-set
        return true
    }

    /**
     * Performs a logical release.
     */
    fun releaseMonitor(monitor: Any) {
        // Decrement the reentrancy depth and remove the acquisition info
        // if the monitor becomes free to acquire by another thread.
        val ai = acquiredMonitors[monitor]!!
        ai.timesAcquired--
        if (ai.timesAcquired == 0) acquiredMonitors.remove(monitor)
    }

    /**
     * Returns `true` if the corresponding threads is waiting on some monitor.
     */
    fun isWaiting(iThread: Int): Boolean {
        val monitor = acquiringMonitors[iThread] ?: return false
        return waitForNotify[iThread] || !canAcquireMonitor(iThread, monitor)
    }

    /**
     * Returns `true` if the monitor is already acquired by
     * the thread [iThread], or if this monitor is free to acquire.
     */
    private fun canAcquireMonitor(iThread: Int, monitor: Any) =
        acquiredMonitors[monitor]?.iThread?.equals(iThread) ?: true

    /**
     * Performs a logical wait, [isWaiting] for the specified thread
     * returns `true` until the corresponding [notify] or [notifyAll]
     * is invoked.
     */
    fun waitOnMonitor(iThread: Int, monitor: Any) {
        // TODO: we can add spurious wakeups here
        check(monitor in acquiredMonitors) { "Monitor should have been acquired by this thread" }
        releaseMonitor(monitor)
        waitForNotify[iThread] = true
        acquiringMonitors[iThread] = monitor
    }

    /**
     * Just notify all thread. Odd threads will have a spurious wakeup
     */
    fun notify(monitor: Any) = notifyAll(monitor)

    /**
     * Performs the logical `notifyAll`.
     */
    fun notifyAll(monitor: Any): Unit = acquiringMonitors.forEachIndexed { iThread, m ->
        if (monitor === m) waitForNotify[iThread] = false
    }

    /**
     * Stores the number of reentrant acquisitions ([timesAcquired])
     * and the number of thread ([iThread]) that holds the monitor.
     */
    private class MonitorAcquiringInfo(val iThread: Int, var timesAcquired: Int)
}

/**
 * This exception is used to finish the execution correctly for managed strategies.
 * Otherwise, there is no way to do it in case of (e.g.) deadlocks.
 * If we just leave it, then the execution will not be halted.
 * If we forcibly pass through all barriers, then we can get another exception due to being in an incorrect state.
 */
internal object ForcibleExecutionFinishException : RuntimeException() {
    // do not create a stack trace -- it simply can be unsafe
    override fun fillInStackTrace() = this
}

private const val COROUTINE_SUSPENSION_CODE_LOCATION = -1 // currently the exact place of coroutine suspension is not known
