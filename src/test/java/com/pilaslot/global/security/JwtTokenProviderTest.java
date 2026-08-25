package com.pilaslot.global.security;

import com.pilaslot.global.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenProviderTest {

    private static final String SECRET = "pilaslot-test-jwt-secret-key-2026-integration";
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void createsTokenWithOnlyMemberIdSubjectAndStandardTimeClaims() {
        JwtTokenProvider provider = providerAt(NOW, SECRET);

        String token = provider.createAccessToken(42L);

        Jws<Claims> parsedToken = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .clock(() -> Date.from(NOW))
                .build()
                .parseSignedClaims(token);
        Claims claims = parsedToken.getPayload();
        assertThat(parsedToken.getHeader().getAlgorithm()).isEqualTo("HS256");
        assertThat(claims.getSubject()).isEqualTo("42");
        assertThat(claims.keySet()).containsExactlyInAnyOrderElementsOf(Set.of("sub", "iat", "exp"));
    }

    @Test
    void extractsMemberIdFromValidToken() {
        JwtTokenProvider provider = providerAt(NOW, SECRET);

        String token = provider.createAccessToken(42L);

        assertThat(provider.getMemberId(token)).isEqualTo(42L);
    }

    @Test
    void rejectsTokenWithInvalidSignature() {
        String token = providerAt(NOW, SECRET).createAccessToken(42L);
        JwtTokenProvider verifier = providerAt(
                NOW,
                "different-test-jwt-secret-key-2026-integration"
        );

        assertTokenError(() -> verifier.getMemberId(token), ErrorCode.INVALID_TOKEN);
    }

    @Test
    void rejectsMalformedToken() {
        assertTokenError(
                () -> providerAt(NOW, SECRET).getMemberId("not-a-jwt"),
                ErrorCode.INVALID_TOKEN
        );
    }

    @Test
    void distinguishesExpiredToken() {
        String token = providerAt(NOW.minus(Duration.ofHours(2)), SECRET)
                .createAccessToken(42L);

        assertTokenError(
                () -> providerAt(NOW, SECRET).getMemberId(token),
                ErrorCode.EXPIRED_TOKEN
        );
    }

    @Test
    void throwsWhenSecretIsMissing() {
        assertThatThrownBy(() -> providerAt(NOW, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.jwt.secret이 설정되지 않았습니다");
    }

    @Test
    void throwsWhenSecretIsBlank() {
        assertThatThrownBy(() -> providerAt(NOW, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.jwt.secret이 설정되지 않았습니다");
    }

    @Test
    void throwsWhenSecretIsEmpty() {
        assertThatThrownBy(() -> providerAt(NOW, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.jwt.secret이 설정되지 않았습니다");
    }

    @Test
    void throwsWhenSecretIsTooShort() {
        assertThatThrownBy(() -> providerAt(NOW, "too-short-secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("최소 32바이트");
    }

    private JwtTokenProvider providerAt(Instant instant, String secret) {
        return new JwtTokenProvider(
                new JwtProperties(secret, Duration.ofHours(1)),
                Clock.fixed(instant, ZoneOffset.UTC)
        );
    }

    private void assertTokenError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(JwtAuthenticationException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }
}
