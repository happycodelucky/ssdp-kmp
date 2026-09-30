/*
 * ssdp-kmp — test assertions for KotlinResult's `Result` (the return type of
 * `SsdpClient.description`).
 */
package com.happycodelucky.ssdp.internal

import com.happycodelucky.kotlinresult.Result
import kotlin.test.fail

/** The success value, failing the test if this is a failure. */
internal fun <T : Any> Result<T>.assertSuccess(): T = getOrNull() ?: fail("expected a success, got $this")

/** The failure's exception as an [E], failing the test if this is a success or a different exception. */
internal inline fun <reified E : Throwable> Result<*>.assertFailure(): E =
    exceptionOrNull() as? E ?: fail("expected a failure holding ${E::class.simpleName}, got $this")
