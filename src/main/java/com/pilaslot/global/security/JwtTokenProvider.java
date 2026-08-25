package com.pilaslot.global.security;

import com.pilaslot.global.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;

@Component
public class JwtTokenProvider {

    private static final int MINIMUM_SECRET_LENGTH_BYTES = 32;

    private final SecretKey secretKey;
    private final JwtParser jwtParser;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtTokenProvider(JwtProperties properties, Clock clock) {
        String secret = properties.secret();
        if (isMissing(secret)) {
            throw new IllegalArgumentException("app.jwt.secret이 설정되지 않았습니다.");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MINIMUM_SECRET_LENGTH_BYTES) {
            throw new IllegalArgumentException(
                    "app.jwt.secret은 최소 %d바이트여야 합니다. 현재 %d바이트."
                            .formatted(MINIMUM_SECRET_LENGTH_BYTES, secretBytes.length)
            );
        }
        this.secretKey = Keys.hmacShaKeyFor(secretBytes);
        this.jwtParser = Jwts.parser()
                .verifyWith(secretKey)
                .clock(() -> Date.from(clock.instant()))
                .build();
        this.properties = properties;
        this.clock = clock;
    }

    private static boolean isMissing(String secret) {
        return secret == null || secret.isBlank();
    }

    public String createAccessToken(Long memberId) {
        Instant now = clock.instant();
        Date issuedAt = Date.from(now);
        Date expiration = Date.from(now.plus(properties.expiration()));
        return Jwts.builder()
                .subject(memberId.toString())
                .issuedAt(issuedAt)
                .expiration(expiration)
                .signWith(secretKey, Jwts.SIG.HS256)
                .compact();
    }

    public Long getMemberId(String token) {
        try {
            Claims claims = jwtParser.parseSignedClaims(token).getPayload();
            return Long.valueOf(claims.getSubject());
        } catch (ExpiredJwtException exception) {
            throw new JwtAuthenticationException(ErrorCode.EXPIRED_TOKEN, exception);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new JwtAuthenticationException(ErrorCode.INVALID_TOKEN, exception);
        }
    }
}
