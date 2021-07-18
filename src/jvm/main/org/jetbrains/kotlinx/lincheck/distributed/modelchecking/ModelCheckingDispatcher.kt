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

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import org.jetbrains.kotlinx.lincheck.distributed.stress.AlreadyIncrementedCounter
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class TaskContext : AbstractCoroutineContextElement(Key) {
    @Volatile
    var shouldSignal = true

    companion object Key : CoroutineContext.Key<TaskContext>
}


class ModelCheckingDispatcher(val runner : DistributedModelCheckingRunner<*, *>) : CoroutineDispatcher() {
    var taskCounter = 0
    val executor = Executors.newSingleThreadExecutor()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
       // println("Submit $block taskCounter=$taskCounter")
        taskCounter++
        //println("Inc counter $block taskCounter=$taskCounter")
        val shouldSignal = (context[TaskContext.Key]?.shouldSignal == true)
        context[TaskContext.Key]?.shouldSignal = false
        executor.submit {
            block.run()
            //println("Finish ${block.hashCode()}")
            //println("task Counter is $taskCounter")
            taskCounter--
            //println("Finish taskCounter=$taskCounter")
            if (taskCounter == 0) {
                runner.signal.signal()
            }
        }
    }

    fun shutdown() = executor.shutdown()
}