package ru.course.helpdesk

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.security.Principal
import java.util.UUID

@RestController
@RequestMapping("/api")
class TicketController(private val service: TicketService) {
    @GetMapping("/me")
    fun me(principal: Principal) = service.actor(principal.name)

    @GetMapping("/tickets")
    fun list(principal: Principal, @RequestParam(defaultValue = "0") offset: Int) =
        service.list(service.actor(principal.name), offset)

    @GetMapping("/queue")
    fun queue(principal: Principal, @RequestParam(defaultValue = "0") offset: Int) =
        service.queue(service.actor(principal.name), offset)

    @GetMapping("/tickets/{id}")
    fun details(principal: Principal, @PathVariable id: UUID, @RequestParam(defaultValue = "0") offset: Int) =
        service.details(service.actor(principal.name), id, offset)

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(principal: Principal, @Valid @RequestBody input: CreateTicket) =
        service.create(service.actor(principal.name), input)

    @PostMapping("/tickets/{id}/claim")
    fun claim(principal: Principal, @PathVariable id: UUID) = service.claim(service.actor(principal.name), id)

    @PostMapping("/tickets/{id}/comments")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun comment(principal: Principal, @PathVariable id: UUID, @Valid @RequestBody input: MessageBody) =
        service.comment(service.actor(principal.name), id, input, false)

    @PostMapping("/tickets/{id}/internal-notes")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun note(principal: Principal, @PathVariable id: UUID, @Valid @RequestBody input: MessageBody) =
        service.comment(service.actor(principal.name), id, input, true)

    @PostMapping("/tickets/{id}/resolve")
    fun resolve(principal: Principal, @PathVariable id: UUID, @Valid @RequestBody input: MessageBody) =
        service.resolve(service.actor(principal.name), id, input)

    @PostMapping("/tickets/{id}/reopen")
    fun reopen(principal: Principal, @PathVariable id: UUID) = service.reopen(service.actor(principal.name), id)
}
