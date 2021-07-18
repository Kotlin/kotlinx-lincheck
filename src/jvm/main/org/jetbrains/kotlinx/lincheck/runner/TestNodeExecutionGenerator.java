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

package org.jetbrains.kotlinx.lincheck.runner;

import kotlin.coroutines.Continuation;
import org.jetbrains.kotlinx.lincheck.Actor;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.commons.TryCatchBlockSorter;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.objectweb.asm.Opcodes.*;
import static org.objectweb.asm.Type.*;

public class TestNodeExecutionGenerator {
    private static final Type[] NO_ARGS = new Type[]{};
    private static final Type OBJECT_TYPE = getType(Object.class);
    private static final Type OBJECT_ARRAY_TYPE = getType(Object[].class);
    private static final Method EMPTY_CONSTRUCTOR = new Method("<init>", VOID_TYPE, NO_ARGS);
    private static final Type TEST_NODE_EXECUTION_TYPE = getType(TestNodeExecution.class);
    private static final Method TEST_NODE_EXECUTION_CONSTRUCTOR;
    private static final String INSTANCE = "INSTANCE";
    private static final Type CONTINUATION_TYPE = getType(Continuation.class);
    private static final Type ILLEGAL_ARGUMENT_EXCEPTION_TYPE = getType(IllegalArgumentException.class);

    // private static final
    private static int generatedClassNumber = 0;

    static {
        try {
            TEST_NODE_EXECUTION_CONSTRUCTOR = Method.getMethod(TestNodeExecution.class.getDeclaredConstructor());
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Creates a {@link TestThreadExecution} instance with specified {@link TestThreadExecution#run()} implementation.
     */
    public static TestNodeExecution create(Runner runner, int iThread, List<Actor> actors
    ) {
        String className = TestNodeExecution.class.getCanonicalName() + generatedClassNumber++;
        String internalClassName = className.replace('.', '/');
        List<Object> objArgs = new ArrayList<>();
        Class<? extends TestNodeExecution> clz = runner.getClassLoader().defineNodeClass(className,
                generateClass(internalClassName, getType(runner.getTestClass()), iThread, actors, objArgs));
        try {
            TestNodeExecution execution = clz.newInstance();
            execution.setRunner(runner);
            execution.objArgs = objArgs.toArray();
            return execution;
        } catch (InstantiationException | IllegalAccessException e) {
            throw new IllegalStateException("Cannot initialize generated execution class", e);
        }
    }

    private static byte[] generateClass(String internalClassName, Type testClassType, int iThread, List<Actor> actors,
                                        List<Object> objArgs) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        CheckClassAdapter cca = new CheckClassAdapter(cw, false);
        cca.visit(52, ACC_PUBLIC + ACC_SUPER, internalClassName, null, TEST_NODE_EXECUTION_TYPE.getInternalName(), null);
        generateConstructor(cca);
        generateMethod(cca, testClassType, actors, objArgs);
        cca.visitEnd();
        String outputFile = "LamportMutex" + iThread + ".class";
        //System.out.println(cw.toByteArray().length);
        /*try (OutputStream outputStream = new FileOutputStream(outputFile)) {
            outputStream.write(cw.toByteArray());
        } catch (IOException ex) {
            ex.printStackTrace();
        }*/
       // throw new RuntimeException();
        return cw.toByteArray();
    }

    private static void generateConstructor(ClassVisitor cv) {
        GeneratorAdapter mv = new GeneratorAdapter(ACC_PUBLIC, EMPTY_CONSTRUCTOR, null, null, cv);
        mv.visitCode();
        mv.loadThis();
        mv.invokeConstructor(TEST_NODE_EXECUTION_TYPE, TEST_NODE_EXECUTION_CONSTRUCTOR);
        mv.visitInsn(RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private static void generateMethod(ClassVisitor cv, Type testType, List<Actor> actors,
                                       List<Object> objArgs) {

        int access = ACC_PUBLIC;
        Method m = new Method("runOperation", OBJECT_TYPE, new Type[]{INT_TYPE, CONTINUATION_TYPE});
        GeneratorAdapter mv = new GeneratorAdapter(access, m,
                // Try-catch blocks sorting is required
                new TryCatchBlockSorter(cv.visitMethod(access, m.getName(), m.getDescriptor(), null, null),
                        access, m.getName(), m.getDescriptor(), null, null)
        );
        mv.visitCode();

        Label[] switchLabels = new Label[actors.size()];
        int[] keys = new int[actors.size()];
        for (int i = 0; i < actors.size(); i++) {
            switchLabels[i] = mv.newLabel();
            keys[i] = i;
        }
        Label illegalArgException = mv.newLabel();

        mv.loadArg(0);
        mv.visitLookupSwitchInsn(illegalArgException, keys, switchLabels);
        // Invoke actors
        for (int i = 0; i < actors.size(); i++) {
            mv.visitLabel(switchLabels[i]);
            mv.loadThis();
            mv.invokeVirtual(TEST_NODE_EXECUTION_TYPE, new Method("getTestInstance", OBJECT_TYPE, NO_ARGS));
            mv.checkCast(testType);
            Actor actor = actors.get(i);
            loadArguments(mv, actor, objArgs);
            // Invoke operation
            Method actorMethod = Method.getMethod(actor.getMethod());
            mv.invokeVirtual(testType, actorMethod);
            mv.box(actorMethod.getReturnType()); // box if needed
            mv.returnValue();
        }
        mv.visitLabel(illegalArgException);
        mv.newInstance(ILLEGAL_ARGUMENT_EXCEPTION_TYPE);
        mv.dup();
        mv.invokeConstructor(ILLEGAL_ARGUMENT_EXCEPTION_TYPE, EMPTY_CONSTRUCTOR);
        mv.throwException();
        mv.visitMaxs(2, 3);
        mv.visitEnd();
    }

    private static void loadArguments(GeneratorAdapter mv, Actor actor, List<Object> objArgs) {
        int nArguments = actor.getArguments().size();
        for (int j = 0; j < nArguments; j++) {
            pushArgumentOnStack(mv, objArgs, actor.getArguments().toArray()[j], actor.getMethod().getParameterTypes()[j]);
        }
        if (actor.isSuspendable()) {
            mv.loadArg(1);
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
            mv.getField(TEST_NODE_EXECUTION_TYPE, "objArgs", OBJECT_ARRAY_TYPE); // this -> objArgs
            mv.push(objArgs.size()); // objArgs -> objArgs, index
            mv.arrayLoad(OBJECT_TYPE); // objArgs, index -> arg
            mv.checkCast(getType(argClass)); // cast object to argument type
            objArgs.add(arg);
        }
    }
}
