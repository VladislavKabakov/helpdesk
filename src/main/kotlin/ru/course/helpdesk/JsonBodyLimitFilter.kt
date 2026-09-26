package ru.course.helpdesk

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayInputStream
import java.io.BufferedReader
import java.io.InputStreamReader

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class JsonBodyLimitFilter : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val type = request.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != "application/json" && type?.endsWith("+json") != true) {
            chain.doFilter(request, response)
            return
        }
        // Also bounds chunked requests; trusting Content-Length alone is insufficient.
        val bytes = request.inputStream.readNBytes(32769)
        if (bytes.size > 32768) {
            response.status = 413
            response.contentType = "application/json;charset=UTF-8"
            response.writer.write("{\"message\":\"Слишком большой запрос\"}")
            return
        }
        val wrapped = object : HttpServletRequestWrapper(request) {
            override fun getInputStream(): ServletInputStream {
                val input = ByteArrayInputStream(bytes)
                return object : ServletInputStream() {
                    override fun read() = input.read()
                    override fun read(b: ByteArray, off: Int, len: Int) = input.read(b, off, len)
                    override fun isFinished() = input.available() == 0
                    override fun isReady() = true
                    override fun setReadListener(listener: ReadListener) =
                        throw UnsupportedOperationException("Only synchronous request processing is supported")
                }
            }
            override fun getReader() = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        }
        chain.doFilter(wrapped, response)
    }
}
