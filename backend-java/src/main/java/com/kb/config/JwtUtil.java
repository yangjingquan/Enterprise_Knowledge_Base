package com.kb.config;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 工具类：生成和解析登录令牌。
 * 载荷含 uid、role、brokerId（可空）、bv（broker.token_version）。
 */
@Component
public class JwtUtil {

    private static final long TTL = 12 * 3600_000L;

    private final SecretKey key;

    public JwtUtil(@Value("${kb.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param brokerId 超管为 null
     * @param bv       签发时的 broker.token_version；超管固定 0
     */
    public String create(Long uid, String username, String role, Long brokerId, int bv) {
        var builder = Jwts.builder()
                .subject(username)
                .claim("uid", uid)
                .claim("role", role)
                .claim("bv", bv)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + TTL))
                .signWith(key);
        if (brokerId != null) {
            builder.claim("brokerId", brokerId);
        }
        return builder.compact();
    }

    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
