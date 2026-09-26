package ru.course.helpdesk

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
@ConditionalOnProperty(name = ["helpdesk.demo.enabled"], havingValue = "true")
class DemoUsers(private val jdbc: JdbcClient, private val encoder: PasswordEncoder, private val env: Environment) : ApplicationRunner {
    @Transactional
    override fun run(args: ApplicationArguments) {
        for ((username, role) in listOf("alice" to "USER", "bob" to "USER", "agent1" to "AGENT", "agent2" to "AGENT")) {
            val password = env.getRequiredProperty("DEMO_${username.uppercase()}_PASSWORD")
            require(password.length >= 20 && password.toByteArray(Charsets.UTF_8).size <= 72) {
                "Demo password must contain at least 20 characters and at most 72 UTF-8 bytes"
            }
            jdbc.sql("""INSERT INTO app_user(id, username, password_hash, role)
                VALUES (:id, :username, :hash, :role) ON CONFLICT (username) DO NOTHING""")
                .param("id", UUID.randomUUID()).param("username", username)
                .param("hash", encoder.encode(password)).param("role", role).update()
        }
    }
}
