package com.processVisualisation.virtualKitchen.auth.security;

import com.processVisualisation.virtualKitchen.auth.model.UserType;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * Issues and validates the signed JWT access tokens used to authenticate
 * requests after login/signup/OTP verification. Tokens are HMAC-signed with a
 * secret key configured via app.jwt.secret, encode the user id (as subject),
 * email, and user type as claims, and expire after app.jwt.expiration-ms
 * milliseconds from issuance. Tokens issued or parsed by this service are
 * consumed by JwtAuthenticationFilter to populate the security context on
 * incoming requests.
 */
@Service
public class JwtService {

    /** HMAC-SHA256 needs a 256-bit key; anything shorter is refused at startup. */
    static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long expirationMs;

    public JwtService(
            @Value("${app.jwt.secret}") String secret,
            @Value("${app.jwt.expiration-ms}") long expirationMs
    ) {
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("app.jwt.secret (JWT_SECRET) must be at least " + MIN_SECRET_BYTES
                    + " bytes; set a long random value in the environment");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.expirationMs = expirationMs;
    }

    /**
     * Generates a signed JWT access token for the given user, valid for
     * app.jwt.expiration-ms milliseconds from the moment it is issued.
     *
     * @param userId the identifier of the user the token authenticates, encoded as the token subject
     * @param email the email address of the user, embedded as a claim
     * @param userType the role/type of the user, embedded as a claim and later used to derive granted authorities
     * @return the compact, signed JWT string
     */
    public String generateToken(Long userId, String email, UserType userType) {
        Instant now = Instant.now();

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("userType", userType.name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusMillis(expirationMs)))
                .signWith(key)
                .compact();
    }

    /**
     * Parses and verifies the signature and expiration of the given token,
     * returning its claims.
     *
     * @param token the compact JWT string to parse
     * @return the verified claims payload of the token
     * @throws io.jsonwebtoken.JwtException if the token is malformed, has an invalid signature, or is expired
     */
    public Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Extracts the user identifier encoded as the subject of the given token.
     *
     * @param token the compact JWT string to parse
     * @return the identifier of the user the token was issued for
     * @throws io.jsonwebtoken.JwtException if the token is malformed, has an invalid signature, or is expired
     */
    public Long extractUserId(String token) {
        return Long.valueOf(parseClaims(token).getSubject());
    }

    /**
     * Extracts the user type claim from the given token.
     *
     * @param token the compact JWT string to parse
     * @return the type/role of the user the token was issued for
     * @throws io.jsonwebtoken.JwtException if the token is malformed, has an invalid signature, or is expired
     */
    public UserType extractUserType(String token) {
        return UserType.valueOf(parseClaims(token).get("userType", String.class));
    }
}
