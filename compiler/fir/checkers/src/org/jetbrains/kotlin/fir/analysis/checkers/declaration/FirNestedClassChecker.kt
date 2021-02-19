/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors.NESTED_CLASS_NOT_ALLOWED
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOn
import org.jetbrains.kotlin.fir.declarations.*

object FirNestedClassChecker : FirRegularClassChecker() {
    override fun check(declaration: FirRegularClass, context: CheckerContext, reporter: DiagnosticReporter) {
        // Local enums / objects / companion objects are handled with different diagnostic codes.
        if (declaration.classKind.isSingleton && declaration.isLocal) return
        val containingDeclaration = context.containingDeclarations.lastOrNull() ?: return

        when (containingDeclaration) {
            is FirRegularClass -> {
                if (!declaration.isInner && (containingDeclaration.isInner || containingDeclaration.isLocal)) {
                    reporter.reportOn(declaration.source, NESTED_CLASS_NOT_ALLOWED, declaration.classKind.description, context)
                }
            }
            is FirClass<*> -> {
                // Since 1.3, enum entries can contain inner classes only.
                // Companion objects are reported with code WRONG_MODIFIER_CONTAINING_DECLARATION instead
                if (containingDeclaration.classKind == ClassKind.ENUM_ENTRY && !declaration.isInner && !declaration.isCompanion) {
                    reporter.reportOn(declaration.source, NESTED_CLASS_NOT_ALLOWED, declaration.classKind.description, context)
                }
            }
        }
    }

    private val ClassKind.description: String
        get() = when (this) {
            ClassKind.CLASS -> "Class"
            ClassKind.INTERFACE -> "Interface"
            ClassKind.ENUM_CLASS -> "Enum class"
            ClassKind.ENUM_ENTRY -> "Enum entry"
            ClassKind.ANNOTATION_CLASS -> "Annotation class"
            ClassKind.OBJECT -> "Object"
        }
}
