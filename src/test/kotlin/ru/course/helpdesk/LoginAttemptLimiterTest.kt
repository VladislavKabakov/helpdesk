package ru.course.helpdesk

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class LoginAttemptLimiterTest {
    @Test fun `window expires at boundary and refusals do not extend it`() {
        val time = AtomicLong()
        val limiter = LoginAttemptLimiter(2, Duration.ofSeconds(5), 2, time::get)
        repeat(2) { assertThat(limiter.retryAfter("one")).isNull() }
        assertThat(limiter.retryAfter("one")).isEqualTo(5)
        time.set(TimeUnit.SECONDS.toNanos(4) + 1)
        assertThat(limiter.retryAfter("one")).isEqualTo(1)
        time.set(TimeUnit.SECONDS.toNanos(5))
        assertThat(limiter.retryAfter("one")).isNull()
    }

    @Test fun `addresses have independent budgets`() {
        val limiter = LoginAttemptLimiter(attemptsPerWindow = 1)
        assertThat(limiter.retryAfter("one")).isNull()
        assertThat(limiter.retryAfter("one")).isNotNull()
        assertThat(limiter.retryAfter("two")).isNull()
    }

    @Test fun `capacity refuses new addresses without evicting active restrictions`() {
        val time = AtomicLong()
        val limiter = LoginAttemptLimiter(1, Duration.ofSeconds(10), 2, time::get)
        assertThat(limiter.retryAfter("one")).isNull()
        time.set(TimeUnit.SECONDS.toNanos(5))
        assertThat(limiter.retryAfter("two")).isNull()
        repeat(100) { assertThat(limiter.retryAfter("new-$it")).isEqualTo(5) }
        assertThat(limiter.retryAfter("one")).isEqualTo(5)
        time.set(TimeUnit.SECONDS.toNanos(10))
        assertThat(limiter.retryAfter("three")).isNull()
        assertThat(limiter.retryAfter("two")).isEqualTo(5)
    }

    @Test fun `expired buckets are removed across repeated full windows`() {
        val time = AtomicLong()
        val limiter = LoginAttemptLimiter(1, Duration.ofSeconds(1), 2, time::get)
        repeat(50) { n ->
            time.set(TimeUnit.SECONDS.toNanos(n.toLong()))
            assertThat(limiter.retryAfter("left-$n")).isNull()
            assertThat(limiter.retryAfter("right-$n")).isNull()
            assertThat(limiter.retryAfter("extra-$n")).isEqualTo(1)
        }
    }

    @Test fun `parallel requests cannot exceed a shared budget`() {
        val limiter = LoginAttemptLimiter(attemptsPerWindow = 10)
        val pool = Executors.newFixedThreadPool(20)
        val ready = CountDownLatch(20)
        val start = CountDownLatch(1)
        try {
            val jobs = (1..20).map {
                pool.submit(Callable {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    limiter.retryAfter("same-address") == null
                })
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertThat(jobs.count { it.get(5, TimeUnit.SECONDS) }).isEqualTo(10)
        } finally { pool.shutdownNow() }
    }
}
