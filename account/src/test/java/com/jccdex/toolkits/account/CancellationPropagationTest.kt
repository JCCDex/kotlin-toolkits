package com.jccdex.toolkits.account

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class CancellationPropagationTest {
    /**
     * M-1: `runCatching` around a suspend call must rethrow `CancellationException` — otherwise
     * the coroutine keeps running after `cancel()`. This mirrors the pattern applied across
     * account/did/nft/app-update/dapp-connect.
     *
     * The observable property is the side-effect flag: with the fix the rethrown
     * CancellationException aborts the body before it reaches `continuedAfterCancel = true`;
     * without it, `runCatching` swallows the cancellation and the body continues.
     */
    @Test
    fun runCatchingWithOnFailureRethrow_propagatesCancellation() =
        runTest {
            var continuedAfterCancel = false
            val job =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { delay(60_000) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrNull()
                    continuedAfterCancel = true
                }
            job.cancelAndJoin()

            assertThat(continuedAfterCancel).isFalse
        }
}
