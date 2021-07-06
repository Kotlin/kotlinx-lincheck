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

import org.jetbrains.kotlinx.lincheck.CrashResult
import org.jetbrains.kotlinx.lincheck.Result
import org.jetbrains.kotlinx.lincheck.runner.TestThreadExecution
import org.jetbrains.kotlinx.lincheck.runner.TestThreadExecutionGenerator
import org.objectweb.asm.Label
import org.objectweb.asm.Type
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import kotlin.reflect.jvm.javaMethod

private val CRASH_ERROR_TYPE = Type.getType(CrashError::class.java)
private val CRASH_RESULT_TYPE = Type.getType(CrashResult::class.java)
private val CRASH_RESULT_CREATE_CRASH_RESULT_METHOD = Method.getMethod(CrashResult::creteCrashResult.javaMethod)
private val CRASH_TYPE = Type.getType(Crash::class.java)
private val CRASH_AWAIT_SYSTEM_CRASH = Method.getMethod(Crash::awaitSystemCrash.javaMethod)
private val SET_USE_CLOCKS = Method("useClocksOnce", Type.VOID_TYPE, emptyArray())
private val TEST_THREAD_EXECUTION_TYPE = Type.getType(TestThreadExecution::class.java)
private val RESULT_TYPE = Type.getType(Result::class.java)

open class ActorCrashHandlerGenerator {
    open fun addCrashTryBlock(start: Label, end: Label, mv: GeneratorAdapter) {}
    open fun addCrashCatchBlock(mv: GeneratorAdapter, resLocal: Int, iLocal: Int, skip: Label) {}
}

class DurableActorCrashHandlerGenerator : ActorCrashHandlerGenerator() {
    private lateinit var handlerLabel: Label

    override fun addCrashTryBlock(start: Label, end: Label, mv: GeneratorAdapter) {
        super.addCrashTryBlock(start, end, mv)
        handlerLabel = mv.newLabel()
        mv.visitTryCatchBlock(start, end, handlerLabel, CRASH_ERROR_TYPE.internalName)
    }

    override fun addCrashCatchBlock(mv: GeneratorAdapter, resLocal: Int, iLocal: Int, skip: Label) {
        super.addCrashCatchBlock(mv, resLocal, iLocal, skip)
        mv.visitLabel(handlerLabel)
        storeExceptionResultFromCrash(mv, resLocal, iLocal, skip)
    }

    private fun storeExceptionResultFromCrash(mv: GeneratorAdapter, resLocal: Int, iLocal: Int, skip: Label) {
        mv.pop()

        mv.loadLocal(resLocal)
        mv.loadLocal(iLocal)

        // Create crash result instance
        mv.invokeStatic(CRASH_RESULT_TYPE, CRASH_RESULT_CREATE_CRASH_RESULT_METHOD)
        mv.checkCast(RESULT_TYPE)
        mv.arrayStore(RESULT_TYPE)

        // clock increment must go before barrier
        TestThreadExecutionGenerator.incrementClock(mv, iLocal)

        // force read clocks for next actor
        mv.loadThis()
        mv.invokeVirtual(TEST_THREAD_EXECUTION_TYPE, SET_USE_CLOCKS)

        mv.invokeStatic(CRASH_TYPE, CRASH_AWAIT_SYSTEM_CRASH)

        mv.goTo(skip)
    }
}

class DetectableExecutionActorCrashHandlerGenerator : ActorCrashHandlerGenerator() {
    private lateinit var startLabel: Label
    private lateinit var handlerLabel: Label

    override fun addCrashTryBlock(start: Label, end: Label, mv: GeneratorAdapter) {
        super.addCrashTryBlock(start, end, mv)
        startLabel = start
        handlerLabel = mv.newLabel()
        mv.visitTryCatchBlock(start, end, handlerLabel, CRASH_ERROR_TYPE.internalName)
    }

    override fun addCrashCatchBlock(mv: GeneratorAdapter, resLocal: Int, iLocal: Int, skip: Label) {
        super.addCrashCatchBlock(mv, resLocal, iLocal, skip)
        val afterActor = mv.newLabel()
        mv.goTo(afterActor)
        mv.visitLabel(handlerLabel)
        mv.pop()
        mv.invokeStatic(CRASH_TYPE, CRASH_AWAIT_SYSTEM_CRASH)
        mv.goTo(startLabel)
        mv.visitLabel(afterActor)
    }
}
