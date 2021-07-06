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

package org.jetbrains.kotlinx.lincheck

import org.jetbrains.kotlinx.lincheck.execution.*
import java.lang.IllegalStateException
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

/**
 * Creates a custom scenario using the specified DSL as in the following example:
 *
 * ```
 * scenario {
 *   initial {
 *     actor(QueueTest::offer, 1)
 *     actor(QueueTest::offer, 2)
 *   }
 *   parallel {
 *     thread {
 *       actor(QueueTest::poll)
 *     }
 *     thread {
 *       actor(QueueTest::poll)
 *     }
 *   }
 * }
 * ```
 */
fun scenario(block: DSLScenarioBuilder.() -> Unit): ExecutionScenario =
    DSLScenarioBuilder().apply(block).buildScenario()

/**
 * Create an actor with the specified [function][f] and [arguments][args].
 */
internal fun actor(f: KFunction<*>, vararg args: Any?, cancelOnSuspension: Boolean = false): Actor {
    val method = f.javaMethod ?: throw IllegalStateException("The function is a constructor or cannot be represented by a Java Method")
    require(method.exceptionTypes.all { Throwable::class.java.isAssignableFrom(it) }) { "Not all declared exceptions are Throwable" }
    return Actor(
        method = method,
        arguments = args.toMutableList(),
        handledExceptions = (method.exceptionTypes as Array<Class<out Throwable>>).toList(),
        cancelOnSuspension = cancelOnSuspension
    )
}

@ScenarioDSLMarker
class DSLThreadScenario : ArrayList<Actor>() {
    /**
     * An actor to be executed
     */
    fun actor(f: KFunction<*>, vararg args: Any?) {
        add(org.jetbrains.kotlinx.lincheck.actor(f, *args))
    }
}

@ScenarioDSLMarker
class DSLParallelScenario : ArrayList<DSLThreadScenario>() {
    /**
     * Define a sequence of actors to be executed in a separate thread
     */
    fun thread(block: DSLThreadScenario.() -> Unit) {
        add(DSLThreadScenario().apply(block))
    }
}

@ScenarioDSLMarker
class DSLScenarioBuilder {
    private val initial = mutableListOf<Actor>()
    private var parallel = mutableListOf<MutableList<Actor>>()
    private val post = mutableListOf<Actor>()
    private var initialSpecified = false
    private var parallelSpecified = false
    private var postSpecified = false

    /**
     * Specifies the initial part of the scenario.
     */
    fun initial(block: DSLThreadScenario.() -> Unit) {
        require(!initialSpecified) { "Redeclaration of the initial part is prohibited." }
        initialSpecified = true
        initial.addAll(DSLThreadScenario().apply(block))
    }

    /**
     * Specifies the parallel part of the scenario.
     */
    fun parallel(block: DSLParallelScenario.() -> Unit) {
        require(!parallelSpecified) { "Redeclaration of the parallel part is prohibited." }
        parallelSpecified = true
        parallel.addAll(DSLParallelScenario().apply(block))
    }

    /**
     * Specifies the post part of the scenario.
     */
    fun post(block: DSLThreadScenario.() -> Unit) {
        require(!postSpecified) { "Redeclaration of tthe post part is prohibited." }
        postSpecified = true
        post.addAll(DSLThreadScenario().apply(block))
    }

    /**
     * Constructs a new [scenario][ExecutionScenario] according to
     * the specified [initial], [parallel], and [post] parts.
     */
    fun buildScenario() = ExecutionScenario(initial, parallel, post)
}

@DslMarker
private annotation class ScenarioDSLMarker
