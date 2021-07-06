package org.jetbrains.kotlinx.lincheck;

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

import org.jetbrains.kotlinx.lincheck.runner.*;
import org.jetbrains.kotlinx.lincheck.strategy.*;
import org.jetbrains.kotlinx.lincheck.strategy.managed.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.Collectors;

import static org.jetbrains.kotlinx.lincheck.TransformationClassLoader.*;
import static org.jetbrains.kotlinx.lincheck.UtilsKt.getCanonicalClassName;
import static org.objectweb.asm.Opcodes.*;

/**
 * This transformer applies required for {@link Strategy} and {@link Runner}
 * class transformations and hines them from others.
 */
public class TransformationClassLoader extends ExecutionClassLoader {
    public static final String REMAPPED_PACKAGE_INTERNAL_NAME = "org/jetbrains/kotlinx/lincheck/tran$f*rmed/";
    public static final String REMAPPED_PACKAGE_CANONICAL_NAME = getCanonicalClassName(REMAPPED_PACKAGE_INTERNAL_NAME);
    public static final int ASM_API = ASM9;

    private final List<Function<ClassVisitor, ClassVisitor>> classTransformers;
    // Cache for classloading and frames computing during the transformation.
    private final Map<String, Class<?>> cache = new ConcurrentHashMap<>();
    private final Remapper remapper;

    public TransformationClassLoader(Strategy strategy, Runner runner) {
        classTransformers = new ArrayList<>();
        // Apply the strategy's transformer at first, then the runner's one.
        if (strategy.needsTransformation()) classTransformers.add(strategy::createTransformer);
        if (runner.needsTransformation()) classTransformers.add(runner::createTransformer);
        remapper = UtilsKt.getRemapperByTransformers(strategy);
    }

    public TransformationClassLoader(Function<ClassVisitor, ClassVisitor> classTransformer) {
        this.classTransformers = Collections.singletonList(classTransformer);
        remapper = null;
    }

    /**
     * Returns `true` if the specified class should not be transformed.
     */
    private static boolean doNotTransform(String className) {
        if (className.startsWith(REMAPPED_PACKAGE_CANONICAL_NAME)) return false;
        if (ManagedStrategyTransformerKt.isImpossibleToTransformApiClass(className)) return true;
        return className.startsWith("sun.") ||
               className.startsWith("java.") ||
               className.startsWith("jdk.internal.") ||
               (className.startsWith("kotlin.") &&
                   !className.startsWith("kotlin.collections.") && // transform kotlin collections
                   !(className.startsWith("kotlin.jvm.internal.Array") && className.contains("Iterator")) && // transform kotlin iterator classes
                   !className.startsWith("kotlin.ranges.") // transform kotlin ranges
               ) ||
               (className.startsWith("org.jetbrains.kotlinx.lincheck.") &&
                   !className.startsWith("org.jetbrains.kotlinx.lincheck.test.") &&
                   !className.equals(ManagedStrategyStateHolder.class.getName())
               ) ||
               className.equals(kotlinx.coroutines.CancellableContinuation.class.getName()) ||
               className.equals(kotlinx.coroutines.CoroutineExceptionHandler.class.getName()) ||
               className.equals(kotlinx.coroutines.CoroutineDispatcher.class.getName());
    }

    /**
     * Returns `true` if the specified class should not be transformed.
     * Note that this method takes into consideration whether class name will be remapped.
     */
    boolean shouldBeTransformed(Class<?> clazz) {
        return !doNotTransform(remapClassName(clazz.getName()));
    }

    /**
     * Remaps class name if needed for transformation
     */
    public String remapClassName(String className) {
        if (remapper != null) {
            String internalName = className.replace('.', '/');
            String remappedInternalName = remapper.mapType(internalName);
            return remappedInternalName.replace('/', '.');
        }
        return className;
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> result = cache.get(name);
            if (result != null) {
                return result;
            }
            if (doNotTransform(name)) {
                result = super.loadClass(name);
                cache.put(name, result);
                return result;
            }
            try {
                byte[] bytes = instrument(originalName(name));
                result = defineClass(name, bytes, 0, bytes.length);
                cache.put(name, result);
                return result;
            } catch (Exception e) {
                throw new IllegalStateException("Cannot transform class " + name, e);
            }
        }
    }

    /**
     * Reads class as resource, instruments it (applies {@link Strategy}'s transformer at first,
     * then {@link Runner}'s) and returns the resulting byte-code.
     *
     * @param className name of the class to be transformed.
     * @return the byte-code of the transformed class.
     * @throws IOException if class could not be read as a resource.
     */
    private byte[] instrument(String className) throws IOException {
        // Create ClassReader
        ClassReader cr = new ClassReader(className);
        // Construct transformation pipeline:
        // apply the strategy's transformer at first,
        // then the runner's one.
        ClassVersionGetter infoGetter = new ClassVersionGetter();
        cr.accept(infoGetter, 0);
        ClassWriter cw = new TransformationClassWriter(infoGetter.getClassVersion(), remapper);
        ClassVisitor cv = cw;
        // The code in this comment block verifies the transformed byte-code and prints it for the specified class,
        // you may need to uncomment it for debug purposes under development.
        // cv = new CheckClassAdapter(cv, false);
        // if (className.equals(YourClass.class.getCanonicalName()))
        //   cv = new TraceClassVisitor(cv, new PrintWriter(System.out));

        for (Function<ClassVisitor, ClassVisitor> ct : classTransformers)
            cv = ct.apply(cv);
        // Get transformed bytecode
        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    /**
     * Returns the original name of the specified class before transformation.
     */
    private String originalName(String className) {
        if (className.startsWith(REMAPPED_PACKAGE_CANONICAL_NAME))
            return className.substring(REMAPPED_PACKAGE_CANONICAL_NAME.length());
        return className;
    }

    @Override
    public URL getResource(String name) {
        return super.getResource(name);
    }
}

/**
 * ClassWriter for the classes transformed by *lincheck* with a correct
 * {@link ClassWriter#getCommonSuperClass} implementation.
 */
class TransformationClassWriter extends ClassWriter {
    private final Remapper remapper;

    public TransformationClassWriter(int classVersion, Remapper remapper) {
        super(classVersion > V1_6 ? COMPUTE_FRAMES : COMPUTE_MAXS);
        this.remapper = remapper;
    }

    /**
     * ASM uses `Class.forName` for given types, however it can lead to cyclic dependencies when loading transformed classes.
     * Thus, we call the original method for non-transformed class names and then just fix it if needed.
     */
    @Override
    protected String getCommonSuperClass(final String type1, final String type2) {
        String result = super.getCommonSuperClass(originalInternalName(type1), originalInternalName(type2));
        if (remapper != null)
            return remapper.map(result);
        return result;
    }

    /**
     * Returns the name of the specified class before it was transformed.
     *
     * Classes from `java.util` package are moved to [TRANSFORMED_PACKAGE_NAME] during transformation,
     * this method changes the package to the original one.
     */
    private String originalInternalName(String internalName) {
        if (internalName.startsWith(REMAPPED_PACKAGE_INTERNAL_NAME))
            return internalName.substring(REMAPPED_PACKAGE_INTERNAL_NAME.length());
        return internalName;
    }
}

/**
 * Visitor for retrieving information of class version needed for making a choice between COMPUTE_FRAMES and COMPUTE_MAXS.
 * COMPUTE_FRAMES implies COMPUTE_MAXS, but is more expensive, so it is used only for classes with version 1.7 or higher.
 */
class ClassVersionGetter extends ClassVisitor {
    private int classVersion;

    public ClassVersionGetter() {
        super(TransformationClassLoader.ASM_API);
    }

    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        this.classVersion = version;
    }

    public int getClassVersion() {
        return classVersion;
    }
}