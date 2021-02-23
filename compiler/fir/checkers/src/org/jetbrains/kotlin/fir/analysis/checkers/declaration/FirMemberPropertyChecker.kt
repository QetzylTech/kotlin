/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.analysis.checkers.declaration

import org.jetbrains.kotlin.contracts.description.isDefinitelyVisited
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirFakeSourceElementKind
import org.jetbrains.kotlin.fir.FirSourceElement
import org.jetbrains.kotlin.fir.analysis.cfa.PropertyInitializationInfo
import org.jetbrains.kotlin.fir.analysis.cfa.PropertyInitializationInfoCollector
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.diagnostics.FirErrors
import org.jetbrains.kotlin.fir.analysis.diagnostics.reportOn
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.declarations.impl.FirDefaultPropertyAccessor
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.ControlFlowGraph
import org.jetbrains.kotlin.fir.resolve.dfa.cfg.NormalPath
import org.jetbrains.kotlin.fir.resolve.dfa.controlFlowGraph
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertyAccessorSymbol
import org.jetbrains.kotlin.lexer.KtTokens

// See old FE's [DeclarationsChecker]
object FirMemberPropertyChecker : FirRegularClassChecker() {
    override fun check(declaration: FirRegularClass, context: CheckerContext, reporter: DiagnosticReporter) {
        val memberProperties = declaration.declarations.filterIsInstance<FirProperty>()

        // A property is known to be initialized only if it is initialized
        //   1) with its own initializing expression;
        //   2) at every class constructor;
        //   3) at any of class's anonymous initializers; or
        //   4) at other property's initializing expression

        // 2) Property can be initialized at constructors. Since it's unknown what constructor will be used, the property can be determined
        // as initialized only if it is initialized at every constructor. The caveat is a constructor that calls another constructor, e.g.,
        //   constructor() { x = ... }
        //   constructor(...): this() { ... }  // x will be initialized via this() delegation

        // 3) Property can be initialized at any of class's anonymous initializers (all of initializers will be executed), e.g.,
        //   init { x = ... }
        //   ...
        //   init { y = ... }

        // 4) Property can be initialized at other property's initializing expression too, e.g.,
        //   val initX = inlineMe { x = ... } // where inlineMe returns the value of the last expression of the lambda

        // Here, we accumulate initialized info of a property per constructor, anonymous initializer, or property initialization
        val initializedInConstructor = mutableMapOf<FirProperty, Boolean>().withDefault { false }
        val initializedInInit = mutableMapOf<FirProperty, Boolean>().withDefault { false }
        val initializedInOtherProperty = mutableMapOf<FirProperty, Boolean>().withDefault { false }

        // To handle the delegated constructor call, we need a cache from constructor to (analyzed) property init info.
        val constructorToData = mutableMapOf<FirConstructor, PropertyInitializationInfo>()

        fun collectInfoFromGraph(
            graph: ControlFlowGraph,
            map: MutableMap<FirProperty, Boolean>,
            acc: (Boolean, Boolean) -> Boolean,
            delegatedConstructor: FirConstructor? = null,
        ) {
            val delegatedInfo =
                if (delegatedConstructor != null && constructorToData.containsKey(delegatedConstructor)) {
                    constructorToData[delegatedConstructor]
                } else null

            val data = PropertyInitializationInfoCollector(memberProperties.map { it.symbol }.toSet()).getData(graph)
            val infoAtExitNode = data[graph.exitNode]?.get(NormalPath)

            val info = when {
                delegatedInfo == null && infoAtExitNode == null -> return
                delegatedInfo == null -> infoAtExitNode
                infoAtExitNode == null -> delegatedInfo
                else -> {
                    // NB: it's not merge, which is conducted at merging points, such as loop condition or when.
                    // Rather, delegated constructor call is the predecessor of the current constructor call, so we should accumulate.
                    delegatedInfo.plus(infoAtExitNode)
                }
            } ?: return

            if (graph.declaration is FirConstructor) {
                constructorToData.putIfAbsent(graph.declaration as FirConstructor, info)
            }

            for (property in memberProperties) {
                if (map.containsKey(property)) {
                    // Accumulation: && for class constructors, || for class's anonymous initializers and property initializations
                    map[property] = acc.invoke(map[property]!!, info[property.symbol]?.isDefinitelyVisited() == true)
                } else {
                    // Initial assignment.
                    map[property] = info[property.symbol]?.isDefinitelyVisited() == true
                }
            }
        }

        val constructorGraphs = declaration.constructors.mapNotNull { it.controlFlowGraphReference?.controlFlowGraph }
        for (graph in constructorGraphs) {
            var delegatedConstructor: FirConstructor? = null
            val delegatedConstructorCall = (graph.declaration as? FirConstructor)?.delegatedConstructor
            if (delegatedConstructorCall != null && delegatedConstructorCall.isThis) {
                delegatedConstructor =
                    (delegatedConstructorCall.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol?.fir as? FirConstructor
            }
            collectInfoFromGraph(graph, initializedInConstructor, Boolean::and, delegatedConstructor)
        }

        val initGraphs = declaration.anonymousInitializers.mapNotNull { it.controlFlowGraphReference?.controlFlowGraph }
        for (graph in initGraphs) {
            collectInfoFromGraph(graph, initializedInInit, Boolean::or)
        }

        val propertyInitGraphs = memberProperties.mapNotNull { it.controlFlowGraphReference?.controlFlowGraph }
        for (graph in propertyInitGraphs) {
            collectInfoFromGraph(graph, initializedInOtherProperty, Boolean::or)
        }

        for (property in memberProperties) {
            val isInitialized = property.initializer != null ||
                    initializedInConstructor.getValue(property) ||
                    initializedInInit.getValue(property) ||
                    initializedInOtherProperty.getValue(property)
            checkProperty(declaration, property, isInitialized, context, reporter)
        }
    }

    private fun checkProperty(
        containingDeclaration: FirRegularClass,
        property: FirProperty,
        isInitialized: Boolean,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val source = property.source ?: return
        if (source.kind is FirFakeSourceElementKind) return
        // If multiple (potentially conflicting) modality modifiers are specified, not all modifiers are recorded at `status`.
        // So, our source of truth should be the full modifier list retrieved from the source.
        val modifierList = with(FirModifierList) { property.source.getModifierList() }
        val hasAbstractModifier = modifierList?.modifiers?.any { it.token == KtTokens.ABSTRACT_KEYWORD } == true
        val isAbstract = property.isAbstract || hasAbstractModifier
        if (containingDeclaration.isInterface &&
            Visibilities.isPrivate(property.visibility) &&
            !isAbstract &&
            (property.getter == null || property.getter is FirDefaultPropertyAccessor)
        ) {
            property.source?.let {
                reporter.reportOn(it, FirErrors.PRIVATE_PROPERTY_IN_INTERFACE, context)
            }
        }

        if (isAbstract) {
            if (!containingDeclaration.canHaveAbstractDeclaration) {
                property.source?.let {
                    reporter.reportOn(
                        it,
                        FirErrors.ABSTRACT_PROPERTY_IN_NON_ABSTRACT_CLASS,
                        property,
                        containingDeclaration,
                        context
                    )
                    return
                }
            }
            property.initializer?.source?.let {
                reporter.reportOn(it, FirErrors.ABSTRACT_PROPERTY_WITH_INITIALIZER, context)
            }
            property.delegate?.source?.let {
                reporter.reportOn(it, FirErrors.ABSTRACT_DELEGATED_PROPERTY, context)
            }

            checkAccessor(property.getter, property.delegate) { src, _ ->
                reporter.reportOn(src, FirErrors.ABSTRACT_PROPERTY_WITH_GETTER, context)
            }
            checkAccessor(property.setter, property.delegate) { src, symbol ->
                if (symbol.fir.visibility == Visibilities.Private && property.visibility != Visibilities.Private) {
                    reporter.reportOn(src, FirErrors.PRIVATE_SETTER_FOR_ABSTRACT_PROPERTY, context)
                } else {
                    reporter.reportOn(src, FirErrors.ABSTRACT_PROPERTY_WITH_SETTER, context)
                }
            }
        }

        checkPropertyInitializer(containingDeclaration, property, isInitialized, reporter, context)

        val hasOpenModifier = modifierList?.modifiers?.any { it.token == KtTokens.OPEN_KEYWORD } == true
        if (hasOpenModifier &&
            containingDeclaration.isInterface &&
            !hasAbstractModifier &&
            property.isAbstract &&
            !isInsideExpectClass(containingDeclaration, context)
        ) {
            property.source?.let {
                reporter.reportOn(it, FirErrors.REDUNDANT_OPEN_IN_INTERFACE, context)
            }
        }
        val isOpen = property.isOpen || hasOpenModifier
        if (isOpen) {
            checkAccessor(property.setter, property.delegate) { src, symbol ->
                if (symbol.fir.visibility == Visibilities.Private && property.visibility != Visibilities.Private) {
                    reporter.reportOn(src, FirErrors.PRIVATE_SETTER_FOR_OPEN_PROPERTY, context)
                }
            }
        }

        checkExpectDeclarationVisibilityAndBody(property, source, modifierList, reporter, context)
    }

    private fun checkAccessor(
        accessor: FirPropertyAccessor?,
        delegate: FirExpression?,
        report: (FirSourceElement, FirPropertyAccessorSymbol) -> Unit,
    ) {
        if (accessor != null && accessor !is FirDefaultPropertyAccessor && accessor.hasBody && delegate == null) {
            accessor.source?.let {
                report.invoke(it, accessor.symbol)
            }
        }
    }
}
