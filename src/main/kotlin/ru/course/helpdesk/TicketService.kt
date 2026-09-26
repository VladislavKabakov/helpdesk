package ru.course.helpdesk

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class TicketService(private val jdbc: JdbcClient) {
    private val ticketMapper = RowMapper { rs, _ ->
        Ticket(rs.getObject("id", UUID::class.java), rs.getObject("requester_id", UUID::class.java),
            rs.getObject("assignee_id", UUID::class.java), rs.getString("title"), rs.getString("description"),
            TicketStatus.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant())
    }

    fun actor(username: String): Actor = jdbc.sql("SELECT id, username, role FROM app_user WHERE username = :name")
        .param("name", username).query { rs, _ ->
            Actor(rs.getObject("id", UUID::class.java), rs.getString("username"), Role.valueOf(rs.getString("role")))
        }.optional().orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Требуется вход") }

    fun list(actor: Actor, offset: Int): List<Ticket> {
        validateOffset(offset)
        val column = if (actor.role == Role.USER) "requester_id" else "assignee_id"
        // The column is a server-side constant; user data is always bound as a parameter.
        return jdbc.sql("SELECT * FROM ticket WHERE $column = :actor ORDER BY created_at DESC, id LIMIT 50 OFFSET :offset")
            .param("actor", actor.id).param("offset", offset).query(ticketMapper).list()
    }

    fun queue(actor: Actor, offset: Int): List<QueueItem> {
        requireRole(actor, Role.AGENT)
        validateOffset(offset)
        return jdbc.sql("SELECT id, title, created_at FROM ticket WHERE status = 'OPEN' ORDER BY created_at, id LIMIT 50 OFFSET :offset")
            .param("offset", offset).query { rs, _ ->
                QueueItem(rs.getObject("id", UUID::class.java), rs.getString("title"), rs.getTimestamp("created_at").toInstant())
            }.list()
    }

    @Transactional
    fun details(actor: Actor, id: UUID, offset: Int): TicketDetails {
        validateOffset(offset)
        val ticket = find(id, lock = true)
        requireAccess(actor, ticket)
        val messages = jdbc.sql("""SELECT * FROM ticket_message WHERE ticket_id = :id
            AND (internal = false OR :agent = true) ORDER BY created_at, id LIMIT 50 OFFSET :offset""")
            .param("id", id).param("agent", actor.role == Role.AGENT)
            .param("offset", offset).query { rs, _ ->
                Message(rs.getObject("id", UUID::class.java), rs.getObject("author_id", UUID::class.java),
                    rs.getString("body"), rs.getBoolean("internal"), rs.getTimestamp("created_at").toInstant())
            }.list()
        return TicketDetails(ticket, messages)
    }

    @Transactional
    fun create(actor: Actor, input: CreateTicket): Ticket {
        requireRole(actor, Role.USER)
        val id = UUID.randomUUID()
        jdbc.sql("""INSERT INTO ticket(id, requester_id, title, description, status)
            VALUES (:id, :actor, :title, :description, 'OPEN')""")
            .param("id", id).param("actor", actor.id).param("title", input.title)
            .param("description", input.description).update()
        audit(actor, id, "CREATED")
        return find(id)
    }

    @Transactional
    fun claim(actor: Actor, id: UUID): Ticket {
        requireRole(actor, Role.AGENT)
        val ticket = find(id, lock = true)
        if (ticket.status != TicketStatus.OPEN) conflict()
        jdbc.sql("UPDATE ticket SET assignee_id = :actor, status = 'IN_PROGRESS', updated_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("actor", actor.id).param("id", id).update()
        audit(actor, id, "CLAIMED")
        return find(id)
    }

    @Transactional
    fun comment(actor: Actor, id: UUID, input: MessageBody, internal: Boolean) {
        if (internal) requireRole(actor, Role.AGENT)
        val ticket = find(id, lock = true)
        requireAccess(actor, ticket)
        if (ticket.status == TicketStatus.RESOLVED) conflict()
        addMessage(actor, id, input.body, internal)
        audit(actor, id, if (internal) "INTERNAL_NOTE_ADDED" else "COMMENTED")
    }

    @Transactional
    fun resolve(actor: Actor, id: UUID, input: MessageBody): Ticket {
        requireRole(actor, Role.AGENT)
        val ticket = find(id, lock = true)
        requireAccess(actor, ticket)
        if (ticket.status != TicketStatus.IN_PROGRESS) conflict()
        addMessage(actor, id, input.body, false)
        changeStatus(id, "RESOLVED")
        audit(actor, id, "RESOLVED")
        return find(id)
    }

    @Transactional
    fun reopen(actor: Actor, id: UUID): Ticket {
        requireRole(actor, Role.USER)
        val ticket = find(id, lock = true)
        requireAccess(actor, ticket)
        if (ticket.status != TicketStatus.RESOLVED) conflict()
        jdbc.sql("UPDATE ticket SET status = 'OPEN', assignee_id = NULL, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("id", id).update()
        audit(actor, id, "REOPENED")
        return find(id)
    }

    private fun find(id: UUID, lock: Boolean = false): Ticket =
        jdbc.sql("SELECT * FROM ticket WHERE id = :id" + if (lock) " FOR UPDATE" else "")
            .param("id", id).query(ticketMapper).optional().orElseThrow { notFound() }

    private fun requireAccess(actor: Actor, ticket: Ticket) {
        val allowed = if (actor.role == Role.USER) actor.id == ticket.requesterId else actor.id == ticket.assigneeId
        if (!allowed) throw notFound()
    }

    private fun requireRole(actor: Actor, role: Role) {
        if (actor.role != role) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Доступ запрещён")
    }

    private fun validateOffset(offset: Int) {
        if (offset !in 0..10000) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Недопустимое смещение страницы")
    }

    private fun changeStatus(id: UUID, status: String) {
        jdbc.sql("UPDATE ticket SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
            .param("status", status).param("id", id).update()
    }

    private fun addMessage(actor: Actor, id: UUID, body: String, internal: Boolean) {
        jdbc.sql("""INSERT INTO ticket_message(id, ticket_id, author_id, body, internal)
            VALUES (:message, :ticket, :actor, :body, :internal)""")
            .param("message", UUID.randomUUID()).param("ticket", id).param("actor", actor.id)
            .param("body", body).param("internal", internal).update()
    }

    private fun audit(actor: Actor, id: UUID, action: String) {
        jdbc.sql("INSERT INTO audit_event(actor_id, ticket_id, action) VALUES (:actor, :ticket, :action)")
            .param("actor", actor.id).param("ticket", id).param("action", action).update()
    }

    private fun notFound() = ResponseStatusException(HttpStatus.NOT_FOUND, "Обращение не найдено")
    private fun conflict(): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, "Операция недоступна в текущем состоянии")
}
