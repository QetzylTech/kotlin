/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.util

import java.io.File

val GRADLE_HOME_FOR_TESTS = File(notNullSystemProperty("gradle.for.tests.user.home"))

fun notNullSystemProperty(prop: String): String =
    System.getProperty(prop) ?: error("System property '$prop' is not set")