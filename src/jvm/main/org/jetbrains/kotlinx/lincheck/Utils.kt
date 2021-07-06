/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck

import kotlinx.coroutines.*
import org.jetbrains.kotlinx.lincheck.CancellableContinuationHolder.storedLastCancellableCont
import org.jetbrains.kotlinx.lincheck.execution.*
import org.jetbrains.kotlinx.lincheck.runner.*
import org.jetbrains.kotlinx.lincheck.strategy.Strategy
import org.jetbrains.kotlinx.lincheck.strategy.managed.*
import org.jetbrains.kotlinx.lincheck.verifier.*
import org.objectweb.asm.commons.*
import java.io.*
import java.lang.ref.*
import java.lang.reflect.Method
import java.util.*
import kotlin.coroutines.*
import kotlin.coroutines.intrinsics.*
import kotlin.reflect.full.*
import kotlin.reflect.jvm.*


fun chooseSequentialSpecification(sequentialSpecificationByUser: Class<*>?, testClass: Class<*>): Class<*> =
    if (sequentialSpecificationByUser === DummySequentialSpecification::class.java || sequentialSpecificationByUser == null) testClass
    else sequentialSpecificationByUser

internal fun executeActor(testInstance: Any, actor: Actor) = executeActor(testInstance, actor, null)

/**
 * Executes the specified actor on the sequential specification instance and returns its result.
 */
internal fun executeActor(
    instance: Any,
    actor: Actor,
    completion: Continuation<Any?>?
): Result {
    try {
        val m = getMethod(instance, actor.method)
        val args = (if (actor.isSuspendable) actor.arguments + completion else actor.arguments)
            .map { it.convertForLoader(instance.javaClass.classLoader) }
        val res = m.invoke(instance, *args.toTypedArray())
        return if (m.returnType.isAssignableFrom(Void.TYPE)) VoidResult else createLincheckResult(res)
    } catch (invE: Throwable) {
        val eClass = (invE.cause ?: invE).javaClass.normalize()
        for (ec in actor.handledExceptions) {
            if (ec.isAssignableFrom(eClass))
                return ExceptionResult.create(eClass)
        }
        throw IllegalStateException("Invalid exception as a result of $actor", invE)
    } catch (e: Exception) {
        e.catch(
            NoSuchMethodException::class.java,
            IllegalAccessException::class.java
        ) {
            throw IllegalStateException("Cannot invoke method " + actor.method, e)
        }
    }
}

internal inline fun executeValidationFunctions(instance: Any, validationFunctions: List<Method>,
                                               onError: (functionName: String, exception: Throwable) -> Unit) {
    for (f in validationFunctions) {
        val validationException = executeValidationFunction(instance, f)
        if (validationException != null) {
            onError(f.name, validationException)
            return
        }
    }
}

private fun executeValidationFunction(instance: Any, validationFunction: Method): Throwable? {
    val m = getMethod(instance, validationFunction)
    try {
        m.invoke(instance)
    } catch (e: Throwable) {
        return e
    }
    return null
}

internal fun <T> Class<T>.normalize() = LinChecker::class.java.classLoader.loadClass(name) as Class<T>

private val methodsCache = WeakHashMap<Class<*>, WeakHashMap<Method, WeakReference<Method>>>()

/**
 * Get the same [method] for [instance] solving the different class loaders problem.
 */
@Synchronized
internal fun getMethod(instance: Any, method: Method): Method {
    val methods = methodsCache.computeIfAbsent(instance.javaClass) { WeakHashMap() }
    return methods[method]?.get() ?: run {
        val m = instance.javaClass.getMethod(method.name, method.parameterTypes)
        methods[method] = WeakReference(m)
        m
    }
}

/**
 * Finds a method withe the specified [name] and (parameters)[parameterTypes]
 * ignoring the difference in class loaders for these parameter types.
 */
private fun Class<out Any>.getMethod(name: String, parameterTypes: Array<Class<out Any>>): Method =
    methods.find { method ->
        method.name == name && method.parameterTypes.map { it.name } == parameterTypes.map { it.name }
    } ?: throw NoSuchMethodException("${getName()}.$name(${parameterTypes.joinToString(",")})")

/**
 * Creates [Result] of corresponding type from any given value.
 *
 * Java [Void] and Kotlin [Unit] classes are represented as [VoidResult].
 *
 * Instances of [Throwable] are represented as [ExceptionResult].
 *
 * The special [COROUTINE_SUSPENDED] value returned when some coroutine suspended it's execution
 * is represented as [NoResult].
 *
 * Success values of [kotlin.Result] instances are represented as either [VoidResult] or [ValueResult].
 * Failure values of [kotlin.Result] instances are represented as [ExceptionResult].
 */
internal fun createLincheckResult(res: Any?, wasSuspended: Boolean = false) = when {
    (res != null && res.javaClass.isAssignableFrom(Void.TYPE)) || res is Unit -> if (wasSuspended) SuspendedVoidResult else VoidResult
    res != null && res is Throwable -> ExceptionResult.create(res.javaClass, wasSuspended)
    res === COROUTINE_SUSPENDED -> Suspended
    res is kotlin.Result<Any?> -> res.toLinCheckResult(wasSuspended)
    else -> ValueResult(res, wasSuspended)
}

private fun kotlin.Result<Any?>.toLinCheckResult(wasSuspended: Boolean) =
    if (isSuccess) {
        when (val value = getOrNull()) {
            is Unit -> if (wasSuspended) SuspendedVoidResult else VoidResult
            // Throwable was returned as a successful result
            is Throwable -> ValueResult(value::class.java, wasSuspended)
            else -> ValueResult(value, wasSuspended)
        }
    } else ExceptionResult.create(exceptionOrNull()!!.let { it::class.java }, wasSuspended)

inline fun <R> Throwable.catch(vararg exceptions: Class<*>, block: () -> R): R {
    if (exceptions.any { this::class.java.isAssignableFrom(it) }) {
        return block()
    } else throw this
}

/**
 * Returns scenario for the specified thread. Note that initial and post parts
 * are represented as threads with ids `0` and `threads + 1` respectively.
 */
internal operator fun ExecutionScenario.get(threadId: Int): List<Actor> = when (threadId) {
    0 -> initExecution
    threads + 1 -> postExecution
    else -> parallelExecution[threadId - 1]
}

/**
 * Returns results for the specified thread. Note that initial and post parts
 * are represented as threads with ids `0` and `threads + 1` respectively.
 */
internal operator fun ExecutionResult.get(threadId: Int): List<Result> = when (threadId) {
    0 -> initResults
    parallelResultsWithClock.size + 1 -> postResults
    else -> parallelResultsWithClock[threadId - 1].map { it.result }
}

internal class StoreExceptionHandler : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
    var exception: Throwable? = null

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        this.exception = exception
    }
}

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
internal fun <T> CancellableContinuation<T>.cancelByLincheck(promptCancellation: Boolean): CancellationResult {
    val exceptionHandler = context[CoroutineExceptionHandler] as StoreExceptionHandler
    exceptionHandler.exception = null
    val cancelled = cancel(cancellationByLincheckException)
    exceptionHandler.exception?.let {
        throw it.cause!! // let's throw the original exception, ignoring the internal coroutines details
    }
    return when {
        cancelled -> CancellationResult.CANCELLED_BEFORE_RESUMPTION
        promptCancellation -> {
            context[Job]!!.cancel() // we should always put a job into the context for prompt cancellation
            CancellationResult.CANCELLED_AFTER_RESUMPTION
        }
        else -> CancellationResult.CANCELLATION_FAILED
    }
}

internal enum class CancellationResult { CANCELLED_BEFORE_RESUMPTION, CANCELLED_AFTER_RESUMPTION, CANCELLATION_FAILED }

@Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
private val cancelCompletedResultMethod = DispatchedTask::class.declaredFunctions.find { it.name ==  "cancelCompletedResult" }!!.javaMethod!!

/**
 * Returns `true` if the continuation was cancelled by [CancellableContinuation.cancel].
 */
fun <T> kotlin.Result<T>.cancelledByLincheck() = exceptionOrNull() === cancellationByLincheckException

private val cancellationByLincheckException = Exception("Cancelled by lincheck")

object CancellableContinuationHolder {
    var storedLastCancellableCont: CancellableContinuation<*>? = null
}

fun storeCancellableContinuation(cont: CancellableContinuation<*>) {
    val t = Thread.currentThread()
    if (t is FixedActiveThreadsExecutor.TestThread) {
        t.cont = cont
    } else {
        storedLastCancellableCont = cont
    }
}

internal fun ExecutionScenario.convertForLoader(loader: ClassLoader) = ExecutionScenario(
    initExecution,
    parallelExecution.map { actors ->
        actors.map { a ->
            val args = a.arguments.map { it.convertForLoader(loader) }.toMutableList()
            // the original `isSuspendable` is used here since `KFunction.isSuspend` fails on transformed classes
            Actor(
                method = a.method.convertForLoader(loader),
                arguments = args,
                handledExceptions = a.handledExceptions,
                cancelOnSuspension = a.cancelOnSuspension,
                allowExtraSuspension = a.allowExtraSuspension,
                blocking = a.blocking,
                causesBlocking = a.causesBlocking,
                promptCancellation = a.promptCancellation,
                isSuspendable = a.isSuspendable,
                threadIdArgsIndices = a.threadIdArgsIndices
            )
        }
    },
    postExecution
)

/**
 * Finds the same method but loaded by the specified (class loader)[loader],
 * the signature can be changed according to the [TransformationClassLoader]'s remapper.
 */
private fun Method.convertForLoader(loader: ClassLoader): Method {
    if (loader !is TransformationClassLoader) return this
    val clazz = declaringClass.convertForLoader(loader)
    val parameterTypes = parameterTypes.map { it.convertForLoader(loader) }
    return clazz.getDeclaredMethod(name, *parameterTypes.toTypedArray())
}

private fun Class<*>.convertForLoader(loader: TransformationClassLoader): Class<*> = if (isPrimitive) this else loader.loadClass(loader.remapClassName(name))

internal fun Any?.convertForLoader(loader: ClassLoader) = when {
    this == null -> this
    loader is TransformationClassLoader && !loader.shouldBeTransformed(this.javaClass) -> this
    this is Serializable -> serialize().run { deserialize(loader) }
    else -> error("The result class should either be always loaded by the system class loader and not be transformed," +
                  " or implement Serializable interface.")
}

internal fun Any?.serialize(): ByteArray = ByteArrayOutputStream().use {
    val oos = ObjectOutputStream(it)
    oos.writeObject(this)
    it.toByteArray()
}

internal fun ByteArray.deserialize(loader: ClassLoader) = ByteArrayInputStream(this).use {
    CustomObjectInputStream(loader, it).run { readObject() }
}

/**
 * ObjectInputStream that uses custom class loader.
 */
private class CustomObjectInputStream(val loader: ClassLoader, inputStream: InputStream) : ObjectInputStream(inputStream) {
    override fun resolveClass(desc: ObjectStreamClass): Class<*> {
        // add `TRANSFORMED_PACKAGE_NAME` prefix in case of TransformationClassLoader and remove otherwise
        val className = if (loader is TransformationClassLoader) loader.remapClassName(desc.name)
                        else desc.name.removePrefix(TransformationClassLoader.REMAPPED_PACKAGE_CANONICAL_NAME)
        return Class.forName(className, true, loader)
    }
}

/**
 * Collects the current thread dump and keeps only those
 * threads that are related to the specified [runner].
 */
internal fun collectThreadDump(runner: Runner) = Thread.getAllStackTraces().filter { (t, _) ->
    t is FixedActiveThreadsExecutor.TestThread && t.runnerHash == runner.hashCode()
}

internal fun getRemapperByTransformers(strategy: Strategy): Remapper? =
    when (strategy) {
        is ManagedStrategy -> JavaUtilRemapper()
        else -> null
    }

internal val String.canonicalClassName get() = this.replace('/', '.')
internal val String.internalClassName get() = this.replace('.', '/')

fun wrapInvalidAccessFromUnnamedModuleExceptionWithDescription(e: Throwable): Throwable {
    if (e.message?.contains("to unnamed module") ?: false) {
        return RuntimeException(ADD_OPENS_MESSAGE, e)
    }
    return e
}

private val ADD_OPENS_MESSAGE = "It seems that you use Java 9+ and the code uses Unsafe or similar constructions that are not accessible from unnamed modules.\n" +
    "Please add the following lines to your test running configuration:\n" +
    "--add-opens java.base/jdk.internal.misc=ALL-UNNAMED\n" +
    "--add-exports java.base/jdk.internal.util=ALL-UNNAMED"