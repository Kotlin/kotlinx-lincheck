package org.jetbrains.kotlinx.lincheck.runner;

/*
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2015 - 2018 Devexperts, LLC
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

import kotlin.coroutines.Continuation;
import org.jetbrains.kotlinx.lincheck.*;
import org.jetbrains.kotlinx.lincheck.nvm.ActorCrashHandlerGenerator;
import org.jetbrains.kotlinx.lincheck.runner.ParallelThreadsRunner.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.commons.TryCatchBlockSorter;
import org.objectweb.asm.util.CheckClassAdapter;

import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Type.*;

/**
 * This class is used to generate {@link TestThreadExecution thread executions}.
 */
public class TestThreadExecutionGenerator {
    private static final Type[] NO_ARGS = new Type[] {};

    private static final Type CLASS_TYPE = getType(Class.class);
    private static final Type OBJECT_TYPE = getType(Object.class);
    private static final Method OBJECT_GET_CLASS = new Method("getClass", CLASS_TYPE, NO_ARGS);
    private static final Type OBJECT_ARRAY_TYPE = getType(Object[].class);
    private static final Type THROWABLE_TYPE = getType(Throwable.class);
    private static final Method EMPTY_CONSTRUCTOR = new Method("<init>", VOID_TYPE, NO_ARGS);

    private static final Type RUNNER_TYPE = getType(Runner.class);
    private static final Method RUNNER_ON_START_METHOD = new Method("onStart", VOID_TYPE, new Type[]{INT_TYPE});
    private static final Method RUNNER_ON_FINISH_METHOD = new Method("onFinish", VOID_TYPE, new Type[]{INT_TYPE});
    private static final Method RUNNER_ON_FAILURE_METHOD = new Method("onFailure", Type.VOID_TYPE, new Type[]{Type.INT_TYPE, THROWABLE_TYPE});
    private static final Method RUNNER_ON_ACTOR_START = new Method("onActorStart", Type.VOID_TYPE, new Type[]{ Type.INT_TYPE });
    private static final Method RUNNER_ON_ENTER_ACTOR_BODY = new Method("onEnterActorBody", Type.VOID_TYPE, new Type[]{ Type.INT_TYPE, Type.INT_TYPE });
    private static final Method RUNNER_ON_EXIT_ACTOR_BODY = new Method("onExitActorBody", Type.VOID_TYPE, new Type[]{Type.INT_TYPE, Type.INT_TYPE});

    private static final Type TEST_THREAD_EXECUTION_TYPE = getType(TestThreadExecution.class);
    private static final Method TEST_THREAD_EXECUTION_CONSTRUCTOR;
    private static final Method TEST_THREAD_EXECUTION_INC_CLOCK = new Method("incClock", VOID_TYPE, NO_ARGS);
    private static final Method TEST_THREAD_EXECUTION_READ_CLOCKS = new Method("readClocks", VOID_TYPE, new Type[]{INT_TYPE});
    private static final Method TEST_THREAD_EXECUTION_RESET_USE_CLOCKS = new Method("resetUseClocksOnce", VOID_TYPE, NO_ARGS);

    private static final Type RESULT_TYPE = getType(Result.class);

    private static final Type NO_RESULT_TYPE = getType(NoResult.class);
    private static final String NO_RESULT_CLASS_NAME = NoResult.class.getCanonicalName().replace('.', '/');

    private static final Type VOID_RESULT_TYPE = getType(VoidResult.class);
    private static final String VOID_RESULT_CLASS_NAME = VoidResult.class.getCanonicalName().replace('.', '/');

    private static final Type SUSPENDED_VOID_RESULT_TYPE = getType(SuspendedVoidResult.class);
    private static final String SUSPENDED_RESULT_CLASS_NAME = SuspendedVoidResult.class.getCanonicalName().replace('.', '/');

    private static final String INSTANCE = "INSTANCE";

    private static final Type VALUE_RESULT_TYPE = getType(ValueResult.class);
    private static final Method VALUE_RESULT_TYPE_CONSTRUCTOR = new Method("<init>", VOID_TYPE, new Type[] {OBJECT_TYPE});

    private static final Type EXCEPTION_RESULT_TYPE = getType(ExceptionResult.class);
    private static final Type RESULT_KT_TYPE = getType(ResultKt.class);
    private static final Method RESULT_KT_CREATE_EXCEPTION_RESULT_METHOD = new Method("createExceptionResult", EXCEPTION_RESULT_TYPE, new Type[] {CLASS_TYPE});

    private static final Type RESULT_ARRAY_TYPE = getType(Result[].class);

    private static final Method RESULT_WAS_SUSPENDED_GETTER_METHOD = new Method("getWasSuspended", BOOLEAN_TYPE, new Type[]{});

    private static final Type PARALLEL_THREADS_RUNNER_TYPE = getType(ParallelThreadsRunner.class);
    private static final Method PARALLEL_THREADS_RUNNER_PROCESS_INVOCATION_RESULT_METHOD = new Method("processInvocationResult", RESULT_TYPE, new Type[]{ OBJECT_TYPE, INT_TYPE, INT_TYPE });
    private static final Method RUNNER_IS_PARALLEL_EXECUTION_COMPLETED_METHOD = new Method("isParallelExecutionCompleted", BOOLEAN_TYPE, new Type[]{});

    private static int generatedClassNumber = 0;

    static {
        try {
            TEST_THREAD_EXECUTION_CONSTRUCTOR = Method.getMethod(TestThreadExecution.class.getDeclaredConstructor());
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Creates a {@link TestThreadExecution} instance with specified {@link TestThreadExecution#run()} implementation.
     */
    public static TestThreadExecution create(Runner runner, int iThread, List<Actor> actors,
                                             List<ParallelThreadsRunner.Completion> completions,
                                             boolean scenarioContainsSuspendableActors
    ) {
        return create(runner, iThread, actors, completions, scenarioContainsSuspendableActors, new ActorCrashHandlerGenerator());
    }

    /**
     * Creates a {@link TestThreadExecution} instance with specified {@link TestThreadExecution#run()} implementation.
     */
    public static TestThreadExecution create(Runner runner, int iThread, List<Actor> actors,
                                             List<ParallelThreadsRunner.Completion> completions,
                                             boolean scenarioContainsSuspendableActors,
                                             ActorCrashHandlerGenerator actorCrashHandlerGenerator
    ) {
        String className = TestThreadExecution.class.getCanonicalName() + generatedClassNumber++;
        String internalClassName = className.replace('.', '/');
        List<Object> objArgs = new ArrayList<>();
        Class<? extends TestThreadExecution> clz = runner.getClassLoader().defineClass(className,
                generateClass(internalClassName, getType(runner.getTestClass()), iThread, actors, objArgs, completions, scenarioContainsSuspendableActors, actorCrashHandlerGenerator));
        try {
            TestThreadExecution execution = clz.newInstance();
            execution.runner = runner;
            execution.objArgs = objArgs.toArray();
            return execution;
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot initialize generated execution class", e);
        }
    }

    private static byte[] generateClass(String internalClassName, Type testClassType, int iThread, List<Actor> actors,
                                        List<Object> objArgs, List<ParallelThreadsRunner.Completion> completions,
                                        boolean scenarioContainsSuspendableActors,
                                        ActorCrashHandlerGenerator actorCrashHandlerGenerator)
    {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        CheckClassAdapter cca = new CheckClassAdapter(cw, false);
        cca.visit(52, ACC_PUBLIC + ACC_SUPER, internalClassName, null, TEST_THREAD_EXECUTION_TYPE.getInternalName(), null);
        generateConstructor(cca);
        generateRun(cca, testClassType, iThread, actors, objArgs, completions, scenarioContainsSuspendableActors, actorCrashHandlerGenerator);
        cca.visitEnd();
        return cw.toByteArray();
    }

    private static void generateConstructor(ClassVisitor cv) {
        GeneratorAdapter mv = new GeneratorAdapter(ACC_PUBLIC, EMPTY_CONSTRUCTOR, null, null, cv);
        mv.visitCode();
        mv.loadThis();
        mv.invokeConstructor(TEST_THREAD_EXECUTION_TYPE, TEST_THREAD_EXECUTION_CONSTRUCTOR);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void generateRun(ClassVisitor cv, Type testType, int iThread, List<Actor> actors,
                                    List<Object> objArgs, List<Completion> completions,
                                    boolean scenarioContainsSuspendableActors,
                                    ActorCrashHandlerGenerator actorCrashHandlerGenerator)
    {
        int access = ACC_PUBLIC;
        Method m = new Method("run", VOID_TYPE, NO_ARGS);
        GeneratorAdapter mv = new GeneratorAdapter(access, m,
                // Try-catch blocks sorting is required
                new TryCatchBlockSorter(cv.visitMethod(access, m.getName(), m.getDescriptor(), null, null),
                        access, m.getName(), m.getDescriptor(), null, null)
        );
        mv.visitCode();
        // Load `results`
        int resLocal = mv.newLocal(RESULT_ARRAY_TYPE);
        mv.loadThis();
        mv.getField(TEST_THREAD_EXECUTION_TYPE, "results", RESULT_ARRAY_TYPE);
        mv.storeLocal(resLocal);
        // Call runner's onStart(iThread) method
        mv.loadThis();
        mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
        mv.push(iThread);
        mv.invokeVirtual(RUNNER_TYPE, RUNNER_ON_START_METHOD);
        // Number of current operation (starts with 0)
        int iLocal = mv.newLocal(INT_TYPE);
        mv.push(0);
        mv.storeLocal(iLocal);

        // Invoke actors
        for (int i = 0; i < actors.size(); i++) {
            readClocksIfNeeded(i, mv);
            Label returnNoResult = mv.newLabel();
            if (scenarioContainsSuspendableActors) {
                // check whether all threads are completed or suspended
                mv.loadThis();
                mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
                mv.invokeVirtual(RUNNER_TYPE, RUNNER_IS_PARALLEL_EXECUTION_COMPLETED_METHOD);
                mv.push(true);
                mv.ifCmp(BOOLEAN_TYPE, GeneratorAdapter.EQ, returnNoResult);
            }
            Actor actor = actors.get(i);
            // Start of try-catch block for exceptions which this actor should handle
            Label handledExceptionHandler = null;
            Label actorCatchBlockStart = mv.newLabel();
            Label actorCatchBlockEnd = mv.newLabel();
            if (actor.getHandlesExceptions()) {
                handledExceptionHandler = mv.newLabel();
                for (Class<? extends Throwable> ec : actor.getHandledExceptions())
                    mv.visitTryCatchBlock(actorCatchBlockStart, actorCatchBlockEnd, handledExceptionHandler, getType(ec).getInternalName());
            }
            Label executionStart = mv.newLabel();
            actorCrashHandlerGenerator.addCrashTryBlock(executionStart, actorCatchBlockEnd, mv);

            // Catch those exceptions that has not been caught yet
            Label unexpectedExceptionHandler = mv.newLabel();
            mv.visitTryCatchBlock(actorCatchBlockStart, actorCatchBlockEnd, unexpectedExceptionHandler, THROWABLE_TYPE.getInternalName());
            mv.visitLabel(actorCatchBlockStart);
            // onActorStart call
            mv.loadThis();
            mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
            mv.push(iThread);
            mv.invokeVirtual(RUNNER_TYPE, RUNNER_ON_ACTOR_START);

            mv.mark(executionStart);

            // onEnterActorBody call
            mv.loadThis();
            mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
            mv.push(iThread);
            mv.push(i);
            mv.invokeVirtual(RUNNER_TYPE, RUNNER_ON_ENTER_ACTOR_BODY);

            // Load result array and index to store the current result
            mv.loadLocal(resLocal);
            mv.push(i);
            if (scenarioContainsSuspendableActors) {
                // push the instance of ParallelThreadsRunner on stack to call it's processInvocationResult method
                mv.loadThis();
                mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
                mv.checkCast(PARALLEL_THREADS_RUNNER_TYPE);
            } else {
                // Prepare to create non-processed value result of actor invocation (in case of no suspendable actors in scenario)
                // Load type of result
                if (actor.getMethod().getReturnType() != void.class) {
                    mv.newInstance(VALUE_RESULT_TYPE);
                    mv.visitInsn(DUP);
                }
            }
            // Load test instance
            mv.loadThis();
            mv.getField(TEST_THREAD_EXECUTION_TYPE, "testInstance", OBJECT_TYPE);
            mv.checkCast(testType);
            // Load arguments for operation
            loadArguments(mv, actor, objArgs, actor.isSuspendable() ? completions.get(i) : null);
            // Invoke operation
            Method actorMethod = Method.getMethod(actor.getMethod());
            mv.invokeVirtual(testType, actorMethod);
            mv.box(actorMethod.getReturnType()); // box if needed
            if (scenarioContainsSuspendableActors) {
                // process result of method invocation with ParallelThreadsRunner's processInvocationResult(result, iThread, i)
                mv.push(iThread);
                mv.push(i);
                mv.invokeVirtual(PARALLEL_THREADS_RUNNER_TYPE, PARALLEL_THREADS_RUNNER_PROCESS_INVOCATION_RESULT_METHOD);
                if (actor.getMethod().getReturnType() == void.class) {
                    createVoidResult(actor, mv);
                }
            } else {
                // Create result
                if (actor.getMethod().getReturnType() == void.class) {
                    createVoidResult(actor, mv);
                } else {
                    mv.invokeConstructor(VALUE_RESULT_TYPE, VALUE_RESULT_TYPE_CONSTRUCTOR);
                }
            }
            // Store result to array
            mv.arrayStore(RESULT_TYPE);

            // onExitActorBody call
            mv.loadThis();
            mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
            mv.push(iThread);
            mv.push(i);
            mv.invokeVirtual(RUNNER_TYPE, RUNNER_ON_EXIT_ACTOR_BODY);

            // End of try-catch block for handled exceptions
            mv.visitLabel(actorCatchBlockEnd);
            Label skipHandlers = mv.newLabel();
            mv.goTo(skipHandlers);

            // Handle exceptions that are valid results
            if (actor.getHandlesExceptions()) {
                // Handled exception handler
                mv.visitLabel(handledExceptionHandler);
                if (scenarioContainsSuspendableActors) {
                    storeExceptionResultFromSuspendableThrowable(mv, resLocal, iLocal, iThread, i);
                } else {
                    storeExceptionResultFromThrowable(mv, resLocal, iLocal);
                }
            }
            // End of try-catch block for all other exceptions
            mv.goTo(skipHandlers);

            Label launchNextActor = mv.newLabel();
            actorCrashHandlerGenerator.addCrashCatchBlock(mv, resLocal, iLocal, launchNextActor);

            // Unexpected exception handler
            mv.visitLabel(unexpectedExceptionHandler);
            // Call onFailure method
            mv.dup();
            int eLocal = mv.newLocal(THROWABLE_TYPE);
            mv.storeLocal(eLocal);
            mv.loadThis();
            mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
            mv.push(iThread);
            mv.loadLocal(eLocal);
            mv.invokeVirtual(RUNNER_TYPE, RUNNER_ON_FAILURE_METHOD);
            // Just throw the exception further
            mv.throwException();
            mv.visitLabel(skipHandlers);

            incrementClock(mv, iLocal);
            mv.visitJumpInsn(GOTO, launchNextActor);

            // write NoResult if all threads were suspended or completed
            mv.visitLabel(returnNoResult);
            mv.loadLocal(resLocal);
            mv.push(i);
            // Load no result type to create new instance of NoResult
            mv.visitFieldInsn(GETSTATIC, NO_RESULT_CLASS_NAME, INSTANCE, NO_RESULT_TYPE.getDescriptor());
            mv.arrayStore(RESULT_TYPE);
            mv.iinc(iLocal, 1);
            mv.visitLabel(launchNextActor);
        }
        // Call runner's onFinish(iThread) method
        mv.loadThis();
        mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
        mv.push(iThread);
        mv.invokeVirtual(RUNNER_TYPE, RUNNER_ON_FINISH_METHOD);
        // Complete the method
        mv.visitInsn(RETURN);
        mv.visitMaxs(2, 2);
        mv.visitEnd();
    }

    public static void incrementClock(GeneratorAdapter mv, int iLocal) {
        mv.loadThis();
        mv.invokeVirtual(TEST_THREAD_EXECUTION_TYPE, TEST_THREAD_EXECUTION_INC_CLOCK);
        // Increment number of current operation
        mv.iinc(iLocal, 1);
    }

    // `actorNumber` starts with 0
    private static void readClocksIfNeeded(int actorNumber, GeneratorAdapter mv) {
        mv.loadThis();
        mv.getField(TEST_THREAD_EXECUTION_TYPE, "useClocksOnce", BOOLEAN_TYPE);
        mv.loadThis();
        mv.getField(TEST_THREAD_EXECUTION_TYPE, "useClocks", BOOLEAN_TYPE);
        mv.visitInsn(IOR);
        Label l = new Label();
        mv.visitJumpInsn(IFEQ, l);
        mv.loadThis();
        mv.push(actorNumber);
        mv.invokeVirtual(TEST_THREAD_EXECUTION_TYPE, TEST_THREAD_EXECUTION_READ_CLOCKS);
        mv.visitLabel(l);
        mv.loadThis();
        mv.invokeVirtual(TEST_THREAD_EXECUTION_TYPE, TEST_THREAD_EXECUTION_RESET_USE_CLOCKS);
    }

    private static void createVoidResult(Actor actor, GeneratorAdapter mv) {
        if (actor.isSuspendable()) {
            Label suspendedVoidResult = mv.newLabel();
            mv.invokeVirtual(RESULT_TYPE, RESULT_WAS_SUSPENDED_GETTER_METHOD);
            mv.push(true);
            mv.ifCmp(BOOLEAN_TYPE, GeneratorAdapter.EQ, suspendedVoidResult);
            mv.visitFieldInsn(GETSTATIC, VOID_RESULT_CLASS_NAME, INSTANCE, VOID_RESULT_TYPE.getDescriptor());
            mv.visitLabel(suspendedVoidResult);
            mv.visitFieldInsn(GETSTATIC, SUSPENDED_RESULT_CLASS_NAME, INSTANCE, SUSPENDED_VOID_RESULT_TYPE.getDescriptor());
        } else {
            mv.pop();
            mv.visitFieldInsn(GETSTATIC, VOID_RESULT_CLASS_NAME, INSTANCE, VOID_RESULT_TYPE.getDescriptor());
        }
    }

    private static void storeExceptionResultFromThrowable(GeneratorAdapter mv, int resLocal, int iLocal) {
        mv.invokeVirtual(OBJECT_TYPE, OBJECT_GET_CLASS);
        int eLocal = mv.newLocal(CLASS_TYPE);
        mv.storeLocal(eLocal);
        mv.loadLocal(resLocal);
        mv.loadLocal(iLocal);
        // Create exception result instance
        mv.loadLocal(eLocal);
        mv.invokeStatic(RESULT_KT_TYPE, RESULT_KT_CREATE_EXCEPTION_RESULT_METHOD);
        mv.checkCast(RESULT_TYPE);
        mv.arrayStore(RESULT_TYPE);
    }

    private static void storeExceptionResultFromSuspendableThrowable(GeneratorAdapter mv, int resLocal, int iLocal, int iThread, int actorId) {
        int eLocal = mv.newLocal(THROWABLE_TYPE);
        mv.storeLocal(eLocal);
        mv.loadLocal(resLocal);
        mv.loadLocal(iLocal);
        // Load runner to call processInvocationResult method
        mv.loadThis();
        mv.getField(TEST_THREAD_EXECUTION_TYPE, "runner", RUNNER_TYPE);
        mv.checkCast(PARALLEL_THREADS_RUNNER_TYPE);
        // Load exception result
        mv.loadLocal(eLocal);
        mv.push(iThread);
        mv.push(actorId);
        // Process result
        mv.invokeVirtual(PARALLEL_THREADS_RUNNER_TYPE, PARALLEL_THREADS_RUNNER_PROCESS_INVOCATION_RESULT_METHOD);
        mv.arrayStore(RESULT_TYPE);
    }

    private static void loadArguments(GeneratorAdapter mv, Actor actor, List<Object> objArgs, Completion completion) {
        int nArguments = actor.getArguments().size();
        for (int j = 0; j < nArguments; j++) {
            pushArgumentOnStack(mv, objArgs, actor.getArguments().toArray()[j], actor.getMethod().getParameterTypes()[j]);
        }
        if (actor.isSuspendable()) {
            pushArgumentOnStack(mv, objArgs, completion, Continuation.class);
        }
    }

    private static void pushArgumentOnStack(GeneratorAdapter mv, List<Object> objArgs, Object arg, Class<?> argClass) {
        if (argClass == boolean.class) {
            mv.push((boolean) arg);
        } else if (argClass == byte.class) {
            mv.push((byte) arg);
        } else if (argClass == char.class) {
            mv.push((char) arg);
        } else if (argClass == short.class) {
            mv.push((short) arg);
        } else if (argClass == int.class) {
            mv.push((int) arg);
        } else if (argClass == long.class) {
            mv.push((long) arg);
        } else if (argClass == float.class) {
            mv.push((float) arg);
        } else if (argClass == double.class) {
            mv.push((double) arg);
        } else if (argClass == String.class) {
            mv.push((String) arg);
        } else { // Object type
            mv.loadThis(); // -> this
            mv.getField(TEST_THREAD_EXECUTION_TYPE, "objArgs", OBJECT_ARRAY_TYPE); // this -> objArgs
            mv.push(objArgs.size()); // objArgs -> objArgs, index
            mv.arrayLoad(OBJECT_TYPE); // objArgs, index -> arg
            mv.checkCast(getType(argClass)); // cast object to argument type
            objArgs.add(arg);
        }
    }
}
