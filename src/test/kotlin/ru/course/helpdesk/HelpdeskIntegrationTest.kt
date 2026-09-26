package ru.course.helpdesk

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.wait.strategy.WaitAllStrategy
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class HelpdeskIntegrationTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var service: TicketService
    private val sessions = mutableMapOf<String, MockHttpSession>()

    companion object {
        @Container @JvmStatic
        val postgres = PostgreSQLContainer<Nothing>("postgres@sha256:b0f9560a2de083e2cc7382e75f808c7381a32852a7ec49117deedb300e552b24").apply {
            waitingFor(WaitAllStrategy()
                .withStrategy(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
                .withStrategy(Wait.forListeningPort()))
        }
        private const val PASSWORD = "Synthetic-test-password-only-2026"
        private val passwordHash = BCryptPasswordEncoder(12).encode(PASSWORD)
        @DynamicPropertySource @JvmStatic
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    @BeforeEach
    fun prepare() {
        jdbc.sql("TRUNCATE audit_event, ticket_message, ticket, app_user RESTART IDENTITY CASCADE").update()
        sessions.clear()
        listOf("alice", "bob", "agent1", "agent2").forEach { name ->
            jdbc.sql("INSERT INTO app_user VALUES (:id, :name, :hash, :role)")
                .param("id", UUID.randomUUID()).param("name", name).param("hash", passwordHash)
                .param("role", if (name.startsWith("agent")) "AGENT" else "USER").update()
        }
    }

    private fun session(name: String): MockHttpSession = sessions.getOrPut(name) {
        val initial = mvc.perform(get("/api/csrf")).andReturn()
        val session = initial.request.session as MockHttpSession
        val token = json.readTree(initial.response.contentAsString)["token"].asText()
        mvc.perform(post("/api/login").session(session).header("X-CSRF-TOKEN", token)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED).param("username", name).param("password", PASSWORD))
            .andExpect(status().isNoContent).andReturn().request.session as MockHttpSession
    }

    private fun postAs(name: String, path: String, body: String? = null, expected: Int = 200): String {
        val session = session(name)
        val token = json.readTree(mvc.perform(get("/api/csrf").session(session))
            .andReturn().response.contentAsString)["token"].asText()
        val request = post(path).session(session).header("X-CSRF-TOKEN", token)
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(body)
        return mvc.perform(request).andExpect(status().`is`(expected)).andReturn().response.contentAsString
    }

    private fun getAs(name: String, path: String, expected: Int = 200) = mvc.perform(get(path).session(session(name)))
        .andExpect(status().`is`(expected)).andReturn().response.contentAsString

    private fun create(): String = json.readTree(postAs("alice", "/api/tickets",
        """{"title":"Не работает принтер","description":"Тестовое описание"}""", 201))["id"].asText()

    @Test fun `anonymous cannot read tickets`() {
        mvc.perform(get("/api/tickets")).andExpect(status().isUnauthorized)
    }

    @Test fun `real login and lifecycle work`() {
        val id = create()
        assertThat(getAs("agent1", "/api/queue")).contains(id).doesNotContain("Тестовое описание")
        postAs("agent1", "/api/tickets/$id/claim")
        postAs("alice", "/api/tickets/$id/comments", """{"body":"Подробности"}""", 204)
        postAs("agent1", "/api/tickets/$id/resolve", """{"body":"Исправлено"}""")
        assertThat(getAs("alice", "/api/tickets/$id")).contains("RESOLVED", "Исправлено")
        postAs("alice", "/api/tickets/$id/reopen")
        assertThat(getAs("alice", "/api/tickets/$id")).contains("OPEN")
        getAs("agent1", "/api/tickets/$id", 404)
    }

    @Test fun `foreign owner cannot read list or ticket`() {
        val id = create()
        assertThat(getAs("bob", "/api/tickets")).isEqualTo("[]")
        getAs("bob", "/api/tickets/$id", 404)
    }

    @ParameterizedTest @ValueSource(strings = ["comments", "reopen"])
    fun `foreign owner cannot mutate ticket`(operation: String) {
        val id = create()
        postAs("bob", "/api/tickets/$id/$operation", if (operation == "comments") """{"body":"X"}""" else null, 404)
    }

    @Test fun `internal notes never reach requester`() {
        val id = create()
        postAs("agent1", "/api/tickets/$id/claim")
        postAs("agent1", "/api/tickets/$id/internal-notes", """{"body":"PRIVATE-MARKER"}""", 204)
        assertThat(getAs("agent1", "/api/tickets/$id")).contains("PRIVATE-MARKER")
        assertThat(getAs("alice", "/api/tickets/$id")).doesNotContain("PRIVATE-MARKER")
        assertThat(getAs("alice", "/api/tickets/$id?offset=1")).doesNotContain("PRIVATE-MARKER")
        assertThat(getAs("alice", "/api/tickets")).doesNotContain("PRIVATE-MARKER")
        getAs("agent2", "/api/tickets/$id", 404)
    }

    @ParameterizedTest @ValueSource(strings = ["claim", "resolve", "internal-notes"])
    fun `user cannot call agent operations`(operation: String) {
        val id = create()
        postAs("alice", "/api/tickets/$id/$operation", if (operation == "claim") null else """{"body":"X"}""", 403)
    }

    @Test fun `unassigned agent cannot read detail or write`() {
        val id = create()
        getAs("agent1", "/api/tickets/$id", 404)
        postAs("agent1", "/api/tickets/$id/comments", """{"body":"X"}""", 404)
        postAs("agent1", "/api/tickets/$id/claim")
        postAs("agent2", "/api/tickets/$id/resolve", """{"body":"X"}""", 404)
    }

    @Test fun `csrf required for mutations and login`() {
        mvc.perform(post("/api/login").param("username", "alice").param("password", PASSWORD)).andExpect(status().isForbidden)
        mvc.perform(post("/api/tickets").session(session("alice")).contentType(MediaType.APPLICATION_JSON)
            .content("""{"title":"X","description":"Y"}""")).andExpect(status().isForbidden)
    }

    @Test fun `logout invalidates session`() {
        val session = session("alice")
        postAs("alice", "/api/logout", expected = 204)
        assertThat(session.isInvalid).isTrue()
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized)
    }

    @Test fun `invalid credentials do not disclose account existence`() {
        fun failure(name: String): String {
            val result = mvc.perform(get("/api/csrf")).andReturn()
            return mvc.perform(post("/api/login").session(result.request.session as MockHttpSession)
                .header("X-CSRF-TOKEN", json.readTree(result.response.contentAsString)["token"].asText())
                .param("username", name).param("password", "wrong"))
                .andExpect(status().isUnauthorized).andReturn().response.contentAsString
        }
        assertThat(failure("alice")).isEqualTo(failure("unknown"))
    }

    @ParameterizedTest @ValueSource(strings = [
        "{\"title\":\" \",\"description\":\"x\"}",
        "{\"title\":\"x\",\"description\":\"y\",\"requesterId\":\"fake\"}",
        "{\"title\":\"x\",\"description\":\"y\",\"status\":\"RESOLVED\"}",
        "{\"title\":null,\"description\":\"x\"}"
    ])
    fun `invalid or extra fields are rejected`(body: String) {
        postAs("alice", "/api/tickets", body, 400)
    }

    @Test fun `long text and invalid ids or pagination are rejected`() {
        postAs("alice", "/api/tickets", json.writeValueAsString(mapOf("title" to "x", "description" to "a".repeat(4001))), 400)
        getAs("alice", "/api/tickets/not-a-uuid", 400)
        getAs("alice", "/api/tickets?offset=-1", 400)
        getAs("alice", "/api/tickets?offset=10001", 400)
    }

    @Test fun `oversized json is rejected before deserialization`() {
        postAs("alice", "/api/tickets", json.writeValueAsString(mapOf("title" to "x", "description" to "a".repeat(40000))), 413)
    }

    @Test fun `sql fragments are stored as data`() {
        val body = "'; DROP TABLE ticket; --"
        val response = postAs("alice", "/api/tickets", json.writeValueAsString(mapOf("title" to body, "description" to body)), 201)
        assertThat(response).contains(body)
        assertThat(jdbc.sql("SELECT count(*) FROM ticket").query(Long::class.java).single()).isEqualTo(1)
    }

    @Test fun `two agents cannot claim same ticket`() {
        val id = UUID.fromString(create())
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val calls = listOf("agent1", "agent2").map { name -> Callable {
                val actor = service.actor(name)
                barrier.await(5, TimeUnit.SECONDS)
                try { service.claim(actor, id); 200 }
                catch (e: org.springframework.web.server.ResponseStatusException) { e.statusCode.value() }
            } }
            assertThat(pool.invokeAll(calls, 10, TimeUnit.SECONDS).map { it.get() }.sorted()).containsExactly(200, 409)
            assertThat(jdbc.sql("SELECT count(*) FROM audit_event WHERE action = 'CLAIMED'").query(Long::class.java).single()).isEqualTo(1)
        } finally { pool.shutdownNow() }
    }

    @Test fun `invalid transition does not write audit or message`() {
        val id = create()
        postAs("alice", "/api/tickets/$id/reopen", expected = 409)
        assertThat(jdbc.sql("SELECT count(*) FROM audit_event").query(Long::class.java).single()).isEqualTo(1)
    }

    @Test fun `resolved ticket rejects comments without changing history`() {
        val id = create()
        postAs("agent1", "/api/tickets/$id/claim")
        postAs("agent1", "/api/tickets/$id/resolve", """{"body":"Готово"}""")
        postAs("alice", "/api/tickets/$id/comments", """{"body":"Не должно сохраниться"}""", 409)
        assertThat(jdbc.sql("SELECT count(*) FROM ticket_message").query(Long::class.java).single()).isEqualTo(1)
    }

    @Test fun `queue and creation enforce role separation`() {
        getAs("alice", "/api/queue", 403)
        postAs("agent1", "/api/tickets", """{"title":"X","description":"Y"}""", 403)
    }

    @Test fun `comment cannot become an internal note through extra field`() {
        val id = create()
        postAs("alice", "/api/tickets/$id/comments", """{"body":"X","internal":true}""", 400)
    }

    @Test fun `login changes session identifier`() {
        val result = mvc.perform(get("/api/csrf")).andReturn()
        val session = result.request.session as MockHttpSession
        val oldId = session.id
        val token = json.readTree(result.response.contentAsString)["token"].asText()
        val login = mvc.perform(post("/api/login").session(session).header("X-CSRF-TOKEN", token)
            .param("username", "alice").param("password", PASSWORD)).andExpect(status().isNoContent).andReturn()
        assertThat(login.request.session!!.id).isNotEqualTo(oldId)
    }

    @Test fun `unsupported read method and absent route preserve status codes`() {
        val session = session("alice")
        val token = json.readTree(mvc.perform(get("/api/csrf").session(session))
            .andReturn().response.contentAsString)["token"].asText()
        mvc.perform(put("/api/tickets").session(session).header("X-CSRF-TOKEN", token))
            .andExpect(status().isMethodNotAllowed)
        getAs("alice", "/api/no-such-route", 404)
    }

    @Test fun `password hashes are salted and never returned`() {
        val encoder = BCryptPasswordEncoder(12)
        assertThat(encoder.matches(PASSWORD, passwordHash)).isTrue()
        assertThat(encoder.encode(PASSWORD)).isNotEqualTo(passwordHash)
        assertThat(getAs("alice", "/api/me")).doesNotContain(PASSWORD, passwordHash, "password")
    }
}
