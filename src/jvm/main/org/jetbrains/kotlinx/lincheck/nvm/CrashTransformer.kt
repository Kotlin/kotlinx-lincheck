/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
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
package org.jetbrains.kotlinx.lincheck.nvm

import org.jetbrains.kotlinx.lincheck.TransformationClassLoader.ASM_API
import org.jetbrains.kotlinx.lincheck.annotations.CrashFree
import org.objectweb.asm.*
import org.objectweb.asm.commons.GeneratorAdapter
import org.objectweb.asm.commons.Method
import kotlin.reflect.jvm.javaMethod

internal open class CrashEnabledVisitor(cv: ClassVisitor, testClass: Class<*>, initial: Boolean = true) :
    ClassVisitor(ASM_API, cv) {
    private val superClassNames = testClass.superClassNames()
    var shouldTransform = initial
        private set
    var name: String? = null
        private set
    var fileName: String? = null
        private set

    override fun visit(
        version: Int,
        access: Int,
        name: String?,
        signature: String?,
        superName: String?,
        interfaces: Array<out String>?
    ) {
        super.visit(version, access, name, signature, superName, interfaces)
        this.name = name
        if (name in superClassNames || name !== null &&
            name.startsWith("org.jetbrains.kotlinx.lincheck.") &&
            !name.startsWith("org.jetbrains.kotlinx.lincheck.test.")
        ) {
            shouldTransform = false
        }
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        if (descriptor == CRASH_FREE_TYPE) {
            shouldTransform = false
        }
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitSource(source: String?, debug: String?) {
        super.visitSource(source, debug)
        fileName = source
    }
}

internal class CrashTransformer(
    cv: ClassVisitor,
    testClass: Class<*>
) : CrashEnabledVisitor(cv, testClass) {
    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        if (!shouldTransform) return mv
        return CrashMethodTransformer(mv, access, name, descriptor, this.name, fileName)
    }
}

private val storeInstructions = listOf(
    Opcodes.AASTORE, Opcodes.IASTORE, Opcodes.FASTORE, Opcodes.BASTORE,
    Opcodes.CASTORE, Opcodes.SASTORE, Opcodes.LASTORE, Opcodes.DASTORE
)

private val returnInstructions = listOf(
    Opcodes.RETURN, Opcodes.ARETURN, Opcodes.DRETURN, Opcodes.FRETURN, Opcodes.IRETURN, Opcodes.LRETURN
)

private val POSSIBLY_CRASH_METHOD = Method.getMethod(Crash::possiblyCrash.javaMethod)
private val CRASH_ERROR_TYPE = Type.getType(CrashError::class.java)
private val THROWABLE_TYPE = Type.getType(Throwable::class.java)
private val CRASH_TYPE = Type.getType(Crash::class.java)
private val CRASH_FREE_TYPE = Type.getDescriptor(CrashFree::class.java)

internal class CrashMethodTransformer(
    mv: MethodVisitor,
    access: Int,
    name: String?,
    descriptor: String?,
    private val className: String?,
    private val fileName: String?
) : GeneratorAdapter(ASM_API, mv, access, name, descriptor) {
    private var shouldTransform = name != "<clinit>" && (access and Opcodes.ACC_BRIDGE) == 0
    private var lineNumber = -1
    private var superConstructorCalled = name != "<init>"

    private fun callCrash() {
        if (!shouldTransform || !superConstructorCalled) return
        push(className)
        push(fileName)
        push(name)
        push(lineNumber)
        invokeStatic(CRASH_TYPE, POSSIBLY_CRASH_METHOD)
    }

    override fun visitLineNumber(line: Int, start: Label?) {
        super.visitLineNumber(line, start)
        lineNumber = line
    }

    override fun visitAnnotation(descriptor: String?, visible: Boolean): AnnotationVisitor {
        if (descriptor == CRASH_FREE_TYPE) {
            shouldTransform = false
        }
        return super.visitAnnotation(descriptor, visible)
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        if (!superConstructorCalled && opcode == Opcodes.INVOKESPECIAL) {
            superConstructorCalled = true
        }
        if (owner !== null && owner.startsWith("org/jetbrains/kotlinx/lincheck/nvm/api/")) {
            callCrash()
        }
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
    }

    override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) {
        if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC) {
            callCrash()
        }
        super.visitFieldInsn(opcode, owner, name, desc)
    }

    override fun visitInsn(opcode: Int) {
        if (opcode in returnInstructions || opcode in storeInstructions) {
            callCrash()
        }
        super.visitInsn(opcode)
    }
}


internal class CrashRethrowTransformer(cv: ClassVisitor) : ClassVisitor(ASM_API, cv) {
    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        val mv = super.visitMethod(access, name, descriptor, signature, exceptions)
        val adapter = GeneratorAdapter(mv, access, name, descriptor)
        return object : MethodVisitor(ASM_API, mv) {
            private val catchLabels = hashSetOf<Label>()

            override fun visitTryCatchBlock(start: Label?, end: Label?, handler: Label?, type: String?) {
                super.visitTryCatchBlock(start, end, handler, type)
                check(type != CRASH_ERROR_TYPE.internalName) { "Catch CrashError is prohibited." }
                if (type == THROWABLE_TYPE.internalName && handler !== null) {
                    catchLabels.add(handler)
                }
            }

            override fun visitLabel(label: Label?) {
                super.visitLabel(label)
                if (label !in catchLabels) return
                adapter.run {
                    val continueCatch = newLabel()
                    dup()
                    instanceOf(CRASH_ERROR_TYPE)
                    ifZCmp(GeneratorAdapter.EQ, continueCatch)
                    throwException()
                    mark(continueCatch)
                }
            }
        }
    }
}

private fun Class<*>.superClassNames(): List<String> {
    val result = mutableListOf<String>()
    var clazz: Class<*>? = this
    while (clazz !== null) {
        result.add(Type.getInternalName(clazz))
        clazz = clazz.superclass
    }
    return result
}
