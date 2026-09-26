package ru.course.helpdesk

import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.servlet.resource.NoResourceFoundException

@RestControllerAdvice
class ApiErrors {
    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ResponseStatusException::class)
    fun expected(error: ResponseStatusException) = ResponseEntity.status(error.statusCode)
        .body(mapOf("message" to (error.reason ?: "Операция отклонена")))

    @ExceptionHandler(MethodArgumentNotValidException::class, HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class)
    fun invalid() = ResponseEntity.badRequest().body(mapOf("message" to "Некорректный запрос"))

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun unsupportedType() = ResponseEntity.status(415).body(mapOf("message" to "Неподдерживаемый формат"))

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun unsupportedMethod() = ResponseEntity.status(405).body(mapOf("message" to "Метод не поддерживается"))

    @ExceptionHandler(NoResourceFoundException::class)
    fun missingResource() = ResponseEntity.status(404).body(mapOf("message" to "Ресурс не найден"))

    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception): ResponseEntity<Map<String, String>> {
        // Exception messages may contain SQL parameters or user-supplied text.
        logger.error("Request failed: {}", error.javaClass.simpleName)
        return ResponseEntity.internalServerError().body(mapOf("message" to "Внутренняя ошибка"))
    }
}
