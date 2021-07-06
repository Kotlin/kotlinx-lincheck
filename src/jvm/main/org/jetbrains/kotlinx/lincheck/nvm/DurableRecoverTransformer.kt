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

import org.jetbrains.kotlinx.lincheck.TransformationClassLoader.ASM_API
import org.jetbrains.kotlinx.lincheck.annotations.DurableRecoverAll
import org.jetbrains.kotlinx.lincheck.annotations.DurableRecoverPerThread
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.objectweb.asm.*
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import kotlin.reflect.jvm.javaMethod

private const val RECOVER_DESCRIPTOR = "()V"
private const val RECOVER_ALL_GENERATED_NAME = "__recoverAll__"
private const val RECOVER_ALL_GENERATED_DESCRIPTOR = RECOVER_DESCRIPTOR
private const val RECOVER_ALL_GENERATED_ACCESS =
    Opcodes.ACC_PRIVATE or Opcodes.ACC_SYNCHRONIZED or Opcodes.ACC_SYNTHETIC
private val CRASH_TYPE = Type.getType(Crash::class.java)
private val CRASH_IS_CRASHED = Method.getMethod(Crash::isCrashed.javaMethod)
private val CRASH_RESET_ALL_CRASHED = Method.getMethod(Crash::resetAllCrashed.javaMethod)
private val OPERATION_TYPE = Type.getType(Operation::class.java)

internal class DurableOperationRecoverTransformer(cv: ClassVisitor, private val _class: Class<*>) : ClassVisitor(ASM_API, cv) {
    private var shouldTransform = false
    internal val recoverAllMethod: java.lang.reflect.Method?
    internal val recoverPerThreadMethod: java.lang.reflect.Method?
    internal lateinit var name: String

    init {
        val recover = recoverMethods(_class)
        recoverAllMethod = recover[0]
        recoverPerThreadMethod = recover[1]
    }

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        this.name = name!!
        shouldTransform = name == Type.getInternalName(_class) ||
            recoverAllMethod !== null && name == Type.getInternalName(recoverAllMethod.declaringClass) ||
            recoverPerThreadMethod !== null && name == Type.getInternalName(recoverPerThreadMethod.declaringClass)
    }

    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (!shouldTransform) return mv
        return DurableRecoverOperationTransformer(this, mv, access, name, descriptor)
    }
}

internal class DurableRecoverAllGenerator(cv: ClassVisitor, _class: Class<*>) : ClassVisitor(ASM_API, cv) {
    private val recoverAllMethod = recoverMethods(_class)[0]

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        if (recoverAllMethod !== null && name == Type.getInternalName(recoverAllMethod.declaringClass))
            generateSynchronizedRecoverAll()
    }

    private fun generateSynchronizedRecoverAll(): Unit = GeneratorAdapter(
        super.visitMethod(
            RECOVER_ALL_GENERATED_ACCESS,
            RECOVER_ALL_GENERATED_NAME,
            RECOVER_ALL_GENERATED_DESCRIPTOR,
            null,
            emptyArray()
        ),
        RECOVER_ALL_GENERATED_ACCESS, RECOVER_ALL_GENERATED_NAME, RECOVER_ALL_GENERATED_DESCRIPTOR
    ).run {
        visitCode()
        generateRecoverCode(Type.getInternalName(recoverAllMethod!!.declaringClass), recoverAllMethod.name)
        visitInsn(Opcodes.RETURN)
        visitMaxs(1, 0)
        visitEnd()
    }
}

private fun GeneratorAdapter.generateRecoverCode(className: String, recoverMethod: String) {
    val endLabel = newLabel()
    visitMethodInsn(
        Opcodes.INVOKESTATIC,
        CRASH_TYPE.internalName,
        CRASH_IS_CRASHED.name,
        CRASH_IS_CRASHED.descriptor,
        false
    )
    visitJumpInsn(Opcodes.IFEQ, endLabel)
    loadThis()
    visitMethodInsn(Opcodes.INVOKEVIRTUAL, className, recoverMethod, RECOVER_DESCRIPTOR, false)
    visitMethodInsn(
        Opcodes.INVOKESTATIC,
        CRASH_TYPE.internalName,
        CRASH_RESET_ALL_CRASHED.name,
        CRASH_RESET_ALL_CRASHED.descriptor,
        false
    )
    visitLabel(endLabel)
}

/** Adds recover call to methods annotated with [Operation]. */
private class DurableRecoverOperationTransformer(
    private val cv: DurableOperationRecoverTransformer,
    mv: MethodVisitor,
    access: Int,
    name: String?,
    descriptor: String?
) : GeneratorAdapter(ASM_API, mv, access, name, descriptor) {
    private var isOperation = false

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        if (descriptor == OPERATION_TYPE.descriptor) isOperation = true
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitCode() {
        super.visitCode()
        if (!isOperation) return

        val recoverAll = cv.recoverAllMethod
        val recoverPerThread = cv.recoverPerThreadMethod
        if (recoverAll !== null) {
            val endLabel = newLabel()
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                CRASH_TYPE.internalName,
                CRASH_IS_CRASHED.name,
                CRASH_IS_CRASHED.descriptor,
                false
            )
            visitJumpInsn(Opcodes.IFEQ, endLabel)
            loadThis()
            mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                cv.name,
                RECOVER_ALL_GENERATED_NAME,
                RECOVER_ALL_GENERATED_DESCRIPTOR,
                false
            )
            mark(endLabel)
        } else if (recoverPerThread !== null) {
            generateRecoverCode(cv.name, recoverPerThread.name)
        }
    }
}

private fun recoverMethods(clazz: Class<*>) = listOf(DurableRecoverAll::class, DurableRecoverPerThread::class)
    .map { a -> clazz.methods.singleOrNull { m -> m.annotations.any { it.annotationClass == a } } }
    .onEach { check(it == null || Type.getMethodDescriptor(it) == RECOVER_DESCRIPTOR) }
