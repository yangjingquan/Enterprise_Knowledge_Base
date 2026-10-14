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
 * JWT 工具类：负责生成和解析登录令牌（token）。
 * <p>
 * 登录成功后，后端把用户名、用户 ID、角色写进 token 返回给前端；
 * 前端之后每次请求都在 Authorization 头里带上 "Bearer token"，后端解析出用户身份。
 */
@Component
public class JwtUtil {

    /** token 有效期：12 小时 */
    private static final long TTL = 12 * 3600_000L;

    /** 签名密钥，由配置项 kb.jwt-secret 生成（至少 32 字节） */
    private final SecretKey key;

    public JwtUtil(@Value("${kb.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 token。
     *
     * @param uid      用户 ID
     * @param username 用户名（作为 token 的 subject）
     * @param role     角色
     * @return 签名后的 token 字符串
     */
    public String create(Long uid, String username, String role) {
        return Jwts.builder()
                .subject(username)
                .claim("uid", uid)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + TTL))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验 token。签名不对或已过期时会抛出异常。
     *
     * @return token 中携带的信息（用户名、uid、role 等）
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
