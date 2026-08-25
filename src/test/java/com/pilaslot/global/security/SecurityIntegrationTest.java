package com.pilaslot.global.security;

import com.pilaslot.support.PostgreSqlTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
@Import({
        PostgreSqlTestContainerConfiguration.class,
        SecurityIntegrationTest.ProtectedEndpointConfiguration.class,
        SecurityIntegrationTest.FixedClockConfiguration.class
})
class SecurityIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final String SECRET = "pilaslot-test-jwt-secret-key-2026-integration";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void returnsUnauthorizedWhenTokenIsMissing() throws Exception {
        mockMvc.perform(get("/test/protected"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("인증이 필요합니다."))
                .andExpect(jsonPath("$.timestamp").value("2026-08-18T09:00:00"))
                .andExpect(jsonPath("$.path").value("/test/protected"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void exposesAuthenticatedMemberPrincipalForValidBearerToken() throws Exception {
        String token = jwtTokenProvider.createAccessToken(42L);

        mockMvc.perform(get("/test/protected")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value(42));
    }

    @Test
    void returnsInvalidTokenForMalformedBearerToken() throws Exception {
        mockMvc.perform(get("/test/protected")
                        .header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.message").value("유효하지 않은 인증 토큰입니다."))
                .andExpect(jsonPath("$.timestamp").value("2026-08-18T09:00:00"))
                .andExpect(jsonPath("$.path").value("/test/protected"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void returnsExpiredTokenForExpiredBearerToken() throws Exception {
        JwtTokenProvider expiredTokenIssuer = new JwtTokenProvider(
                new JwtProperties(SECRET, Duration.ofHours(1)),
                Clock.fixed(NOW.minus(Duration.ofHours(2)), ZoneId.of("Asia/Seoul"))
        );
        String token = expiredTokenIssuer.createAccessToken(42L);

        mockMvc.perform(get("/test/protected")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("EXPIRED_TOKEN"))
                .andExpect(jsonPath("$.message").value("인증 토큰이 만료되었습니다."))
                .andExpect(jsonPath("$.timestamp").value("2026-08-18T09:00:00"))
                .andExpect(jsonPath("$.path").value("/test/protected"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }

    @Test
    void keepsClassSessionListPublic() throws Exception {
        mockMvc.perform(get("/api/v1/class-sessions")
                        .param("weekStart", "2026-08-17"))
                .andExpect(status().isOk());
    }

    @Test
    void keepsActuatorHealthPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void noLongerMapsNestedClassSessionReservationEndpoint() throws Exception {
        String token = jwtTokenProvider.createAccessToken(42L);

        mockMvc.perform(post("/api/v1/class-sessions/10/reservations")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void noLongerMapsMemberMeReservationsEndpoint() throws Exception {
        String token = jwtTokenProvider.createAccessToken(42L);

        mockMvc.perform(get("/api/v1/members/me/reservations")
                        .header("Authorization", "Bearer " + token)
                        .param("weekStart", "2026-08-17"))
                .andExpect(status().isNotFound());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProtectedEndpointConfiguration {

        @Bean
        ProtectedController protectedController() {
            return new ProtectedController();
        }
    }

    @RestController
    static class ProtectedController {

        @GetMapping("/test/protected")
        ProtectedResponse protectedEndpoint(
                @AuthenticationPrincipal AuthenticatedMember authenticatedMember
        ) {
            return new ProtectedResponse(authenticatedMember.memberId());
        }
    }

    record ProtectedResponse(Long memberId) {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfiguration {

        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        }
    }
}
