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

package org.jetbrains.kotlinx.lincheck.nvm

import org.jetbrains.kotlinx.lincheck.execution.ExecutionScenario
import org.objectweb.asm.ClassVisitor

interface ExecutionCallback {
    fun onStart(iThread: Int)
    fun beforeInit(scenario: ExecutionScenario, recoverModel: RecoverabilityModel)
    fun beforeParallel(threads: Int)
    fun beforePost()
    fun afterPost()
    fun onBeforeActorStart()
    fun onActorStart(iThread: Int)
    fun onAfterActorStart()
    fun onFinish(iThread: Int)
    fun getCrashes(): List<List<CrashError>>
    fun reset()
}

private object NoRecoverExecutionCallBack : ExecutionCallback {
    override fun onStart(iThread: Int) {}
    override fun beforeInit(scenario: ExecutionScenario, recoverModel: RecoverabilityModel) {}
    override fun beforeParallel(threads: Int) {}
    override fun beforePost() {}
    override fun afterPost() {}
    override fun onBeforeActorStart() {}
    override fun onActorStart(iThread: Int) {}
    override fun onAfterActorStart() {}
    override fun onFinish(iThread: Int) {}
    override fun getCrashes() = emptyList<List<CrashError>>()
    override fun reset() {}
}

private object RecoverExecutionCallback : ExecutionCallback {
    override fun onStart(iThread: Int) = NVMState.onStart(iThread)
    override fun beforeInit(scenario: ExecutionScenario, recoverModel: RecoverabilityModel) =
        NVMState.beforeInit(scenario, recoverModel)

    override fun beforeParallel(threads: Int) = NVMState.beforeParallel(threads)
    override fun beforePost() = NVMState.beforePost()
    override fun afterPost() = NVMState.afterPost()
    override fun onBeforeActorStart() = NVMState.onBeforeActorStart()
    override fun onActorStart(iThread: Int) = NVMState.onActorStart(iThread)
    override fun onAfterActorStart() = NVMState.onAfterActorStart()
    override fun onFinish(iThread: Int) = NVMState.onFinish(iThread)
    override fun getCrashes(): List<List<CrashError>> = NVMState.clearCrashes().toList()
    override fun reset() = NVMState.reset()
}


enum class StrategyRecoveryOptions {
    STRESS, MANAGED;

    fun createCrashTransformer(cv: ClassVisitor, clazz: Class<*>): ClassVisitor = when (this) {
        STRESS -> CrashTransformer(cv, clazz)
        MANAGED -> cv // add this transformer in ManagedStrategyTransformer
    }
}

enum class Recover {
    NO_RECOVER,
    NRL,
    NRL_NO_CRASHES,
    DURABLE,
    DETECTABLE_EXECUTION,
    DURABLE_NO_CRASHES;

    fun createModel(strategyRecoveryOptions: StrategyRecoveryOptions) = when (this) {
        NO_RECOVER -> NoRecoverModel
        NRL -> NRLModel(true, strategyRecoveryOptions)
        NRL_NO_CRASHES -> NRLModel(false, strategyRecoveryOptions)
        DURABLE -> DurableModel(true, strategyRecoveryOptions)
        DETECTABLE_EXECUTION -> DetectableExecutionModel(strategyRecoveryOptions)
        DURABLE_NO_CRASHES -> DurableModel(false, strategyRecoveryOptions)
    }
}

interface RecoverabilityModel {
    val crashes: Boolean

    fun needsTransformation(): Boolean
    fun createTransformer(cv: ClassVisitor, clazz: Class<*>): ClassVisitor
    fun createActorCrashHandlerGenerator(): ActorCrashHandlerGenerator
    fun systemCrashProbability(): Float
    fun defaultExpectedCrashes(): Int
    fun createExecutionCallback(): ExecutionCallback
    val awaitSystemCrashBeforeThrow: Boolean
}

internal object NoRecoverModel : RecoverabilityModel {
    override val crashes get() = false
    override fun needsTransformation() = false
    override fun createTransformer(cv: ClassVisitor, clazz: Class<*>) = cv
    override fun createActorCrashHandlerGenerator() = ActorCrashHandlerGenerator()
    override fun systemCrashProbability() = 0.0f
    override fun defaultExpectedCrashes() = 0
    override fun createExecutionCallback(): ExecutionCallback = NoRecoverExecutionCallBack
    override val awaitSystemCrashBeforeThrow get() = true
}

private class NRLModel(
    override val crashes: Boolean,
    private val strategyRecoveryOptions: StrategyRecoveryOptions
) : RecoverabilityModel {
    override fun needsTransformation() = true
    override fun createActorCrashHandlerGenerator() = ActorCrashHandlerGenerator()
    override fun systemCrashProbability() = 0.1f
    override fun defaultExpectedCrashes() = 10
    override fun createExecutionCallback(): ExecutionCallback = RecoverExecutionCallback
    override val awaitSystemCrashBeforeThrow get() = true
    override fun createTransformer(cv: ClassVisitor, clazz: Class<*>): ClassVisitor {
        var result: ClassVisitor = RecoverabilityTransformer(cv)
        if (crashes) {
            result = strategyRecoveryOptions.createCrashTransformer(result, clazz)
        }
        return result
    }
}

private open class DurableModel(
    override val crashes: Boolean,
    val strategyRecoveryOptions: StrategyRecoveryOptions
) : RecoverabilityModel {
    override fun needsTransformation() = true
    override fun createActorCrashHandlerGenerator(): ActorCrashHandlerGenerator = DurableActorCrashHandlerGenerator()
    override fun systemCrashProbability() = 1.0f
    override fun defaultExpectedCrashes() = 1
    override fun createExecutionCallback(): ExecutionCallback = RecoverExecutionCallback
    override val awaitSystemCrashBeforeThrow get() = false
    override fun createTransformer(cv: ClassVisitor, clazz: Class<*>): ClassVisitor {
        var result: ClassVisitor = DurableOperationRecoverTransformer(cv, clazz)
        if (crashes) {
            result = strategyRecoveryOptions.createCrashTransformer(result, clazz)
        }
        return result
    }
}

private class DetectableExecutionModel(strategyRecoveryOptions: StrategyRecoveryOptions) :
    DurableModel(true, strategyRecoveryOptions) {
    override fun createActorCrashHandlerGenerator() = DetectableExecutionActorCrashHandlerGenerator()
    override fun defaultExpectedCrashes() = 5
}
