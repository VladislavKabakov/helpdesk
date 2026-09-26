package ru.course.helpdesk

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

enum class Role { USER, AGENT }
enum class TicketStatus { OPEN, IN_PROGRESS, RESOLVED }
data class Actor(val id: UUID, val username: String, val role: Role)
data class Ticket(
    val id: UUID,
    val requesterId: UUID,
    val assigneeId: UUID?,
    val title: String,
    val description: String,
    val status: TicketStatus,
    val createdAt: Instant,
)
data class Message(val id: UUID, val authorId: UUID, val body: String, val internal: Boolean, val createdAt: Instant)
data class TicketDetails(val ticket: Ticket, val messages: List<Message>)
data class QueueItem(val id: UUID, val title: String, val createdAt: Instant)
data class CreateTicket(
    @field:NotBlank @field:Size(max = 120) val title: String,
    @field:NotBlank @field:Size(max = 4000) val description: String,
)
data class MessageBody(@field:NotBlank @field:Size(max = 4000) val body: String)
