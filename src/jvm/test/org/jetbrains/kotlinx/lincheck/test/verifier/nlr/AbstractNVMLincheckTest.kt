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

package org.jetbrains.kotlinx.lincheck.test.verifier.nlr

import org.jetbrains.kotlinx.lincheck.Options
import org.jetbrains.kotlinx.lincheck.checkImpl
import org.jetbrains.kotlinx.lincheck.nvm.CrashError
import org.jetbrains.kotlinx.lincheck.nvm.Recover
import org.jetbrains.kotlinx.lincheck.strategy.IncorrectResultsFailure
import org.jetbrains.kotlinx.lincheck.strategy.LincheckFailure
import org.jetbrains.kotlinx.lincheck.strategy.UnexpectedExceptionFailure
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.jetbrains.kotlinx.lincheck.strategy.stress.StressOptions
import org.jetbrains.kotlinx.lincheck.test.checkTraceHasNoLincheckEvents
import org.junit.Test
import java.lang.IllegalStateException
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.KClass

abstract class AbstractNVMLincheckTest(
    private val model: Recover,
    private val threads: Int,
    private val sequentialSpecification: KClass<*>,
    private val minimizeFailedScenario: Boolean = true,
    private vararg val expectedFailures: KClass<out LincheckFailure>
) {
    open fun <O : Options<O, *>> O.customize() {}
    open fun ModelCheckingOptions.customize() {}
    open fun StressOptions.customize() {}
    open val expectedExceptions = emptyList<KClass<out Throwable>>()

    private fun <O : Options<O, *>> O.runInternalTest() {
        try {
            val failure: LincheckFailure? = checkImpl(this@AbstractNVMLincheckTest::class.java)
            if (failure === null) {
                assert(expectedFailures.isEmpty() && expectedExceptions.isEmpty()) {
                    "This test should fail, but no error has been occurred (see the logs for details)"
                }
            } else {
                failure.trace?.let { checkTraceHasNoLincheckEvents(it.toString()) }
                if (failure is UnexpectedExceptionFailure) {
                    assert(failure.exception !is CrashError) { "Crash error must not be thrown: \n $failure" }
                    if (failure.exception::class !in expectedExceptions) throw failure.exception
                    return
                }
                assert(failure::class in expectedFailures) {
                    "This test has failed with an unexpected error: \n $failure"
                }
            }
        } catch (e: Throwable) {
            assert(e !is CrashError) { "Crash error must not be thrown" }
            var exception = e
            if (e is IllegalStateException) {
                val c = e.cause
                if (c !== null && c is InvocationTargetException) {
                    exception = c.targetException
                }
            }
            if (exception::class !in expectedExceptions) throw exception
        }
    }

    @Test
    fun testWithStressStrategy(): Unit = StressOptions().run {
        commonConfiguration()
        customize()
        runInternalTest()
    }

    @Test
    fun testWithModelCheckingStrategy(): Unit = ModelCheckingOptions().run {
        commonConfiguration()
        customize()
        runInternalTest()
    }

    private fun <O : Options<O, *>> O.commonConfiguration(): Unit = run {
        threads(threads)
        recover(model)
        minimizeFailedScenario(minimizeFailedScenario)
        sequentialSpecification(sequentialSpecification.java)
        customize()
    }
}

abstract class AbstractNVMLincheckFailingTest(
    model: Recover,
    threads: Int,
    sequentialSpecification: KClass<*>,
    minimizeFailedScenario: Boolean = false,
    vararg expectedFailures: KClass<out LincheckFailure>
) : AbstractNVMLincheckTest(
    model,
    threads,
    sequentialSpecification,
    minimizeFailedScenario,
    *(expectedFailures.toList().plus(IncorrectResultsFailure::class).toTypedArray())
)
