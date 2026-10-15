package ru.course.helpdesk

import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@Configuration
class SecurityConfig {
    @Bean
    fun loginAttemptLimiter() = LoginAttemptLimiter()

    @Bean
    fun passwordEncoder() = BCryptPasswordEncoder(12)

    @Bean
    fun users(jdbc: JdbcClient) = UserDetailsService { username ->
        jdbc.sql("SELECT username, password_hash, role FROM app_user WHERE username = :username")
            .param("username", username)
            .query { rs, _ ->
                User.withUsername(rs.getString("username"))
                    .password(rs.getString("password_hash")).roles(rs.getString("role")).build()
            }.optional().orElseThrow { UsernameNotFoundException("Invalid credentials") }
    }

    @Bean
    fun filterChain(http: HttpSecurity, limiter: LoginAttemptLimiter): SecurityFilterChain {
        http.authorizeHttpRequests {
            it.requestMatchers("/api/csrf", "/health", "/error").permitAll()
                .requestMatchers("/api/**").authenticated().anyRequest().denyAll()
        }
        // CSRF and session fixation protection remain enabled, including for login/logout.
        http.addFilterBefore(LoginRateLimitFilter(limiter), UsernamePasswordAuthenticationFilter::class.java)
        http.formLogin {
            it.loginProcessingUrl("/api/login")
                .successHandler { _, response, _ -> response.status = 204 }
                .failureHandler { _, response, _ -> error(response, 401, "Неверные учётные данные") }
                .permitAll()
        }
        http.logout {
            it.logoutUrl("/api/logout").deleteCookies("JSESSIONID")
                .logoutSuccessHandler { _, response, _ -> response.status = 204 }
        }
        http.exceptionHandling {
            it.authenticationEntryPoint { _, response, _ -> error(response, 401, "Требуется вход") }
                .accessDeniedHandler { _, response, _ -> error(response, 403, "Доступ запрещён") }
        }
        http.requestCache { it.disable() }
        return http.build()
    }

    private fun error(response: HttpServletResponse, status: Int, message: String) {
        response.status = status
        response.contentType = "application/json;charset=UTF-8"
        response.writer.write("{\"message\":\"$message\"}")
    }
}

@RestController
class SessionController {
    @GetMapping("/api/csrf")
    fun csrf(token: CsrfToken) = mapOf("token" to token.token, "headerName" to token.headerName)

    @GetMapping("/health")
    fun health() = mapOf("status" to "UP")
}
