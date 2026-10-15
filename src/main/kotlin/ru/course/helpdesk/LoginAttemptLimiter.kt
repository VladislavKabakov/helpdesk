package ru.course.helpdesk

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

/** Fixed windows per transport address; rejected requests do not extend a window. */
class LoginAttemptLimiter(
    private val attemptsPerWindow: Int = 10,
    window: Duration = Duration.ofMinutes(1),
    private val maxClients: Int = 10_000,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val windowNanos = window.toNanos()
    private data class Window(val started: Long, var attempts: Int)
    // Insertion order also orders expiration: existing windows are never renewed in place.
    private val clients = LinkedHashMap<String, Window>()

    init {
        require(attemptsPerWindow > 0 && maxClients > 0)
        require(windowNanos in 1..Duration.ofHours(1).toNanos())
    }

    /** null means allowed; otherwise returns Retry-After in whole seconds, rounded up. */
    @Synchronized
    fun retryAfter(address: String): Long? {
        val now = nanoTime()
        val entries = clients.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (now - entry.value.started < windowNanos) break
            entries.remove()
        }
        val existing = clients[address]
        if (existing != null) {
            if (existing.attempts >= attemptsPerWindow) return remaining(now, existing.started)
            existing.attempts++
            return null
        }
        // Do not evict an active bucket: eviction would let address churn bypass the limit.
        if (clients.size >= maxClients) return remaining(now, clients.values.first().started)
        clients[address] = Window(now, 1)
        return null
    }

    private fun remaining(now: Long, started: Long): Long =
        ((windowNanos - (now - started) + 999_999_999L) / 1_000_000_000L).coerceAtLeast(1)
}

class LoginRateLimitFilter(private val limiter: LoginAttemptLimiter) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.method == "POST" && request.servletPath == "/api/login") {
            // Forwarded headers are deliberately ignored; deployment has no trusted proxy.
            val retryAfter = limiter.retryAfter(request.remoteAddr)
            if (retryAfter != null) {
                response.status = 429
                response.setHeader("Retry-After", retryAfter.toString())
                response.setHeader("Cache-Control", "no-store")
                response.contentType = "application/json;charset=UTF-8"
                response.writer.write("{\"message\":\"Слишком много попыток входа. Повторите позже\"}")
                return
            }
        }
        chain.doFilter(request, response)
    }
}
