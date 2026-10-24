package com.kb.config;

import io.jsonwebtoken.Claims;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Spring Security 安全配置。
 * <p>
 * 整体思路：
 * 1. 不使用 Session，完全依靠 JWT 认证（无状态）；
 * 2. 登录接口 /api/auth/** 放行，其余接口都必须带有效 token；
 * 3. 自定义一个过滤器，从请求头解析 token，并把用户身份放进 Spring Security 上下文。
 */
@Configuration
public class SecurityConfig {

    /**
     * 允许跨域的来源，逗号分隔（环境变量 CORS_ALLOWED_ORIGINS）。
     * 默认 "*"（开发时前端 5173 端口直连后端）；生产在 docker-compose.prod.yml 中设为 https://kb.nexbyte.top。
     * 支持通配模式，如 https://*.example.com。
     */
    @org.springframework.beans.factory.annotation.Value("${kb.cors.allowed-origins:*}")
    private String allowedOrigins;

    /** 密码加密器：使用 BCrypt 单向加密，校验时用 matches() 比对 */
    @Bean
    BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 配置安全过滤链 */
    @Bean
    SecurityFilterChain chain(HttpSecurity http, JwtUtil jwt) throws Exception {
        return http
                // 前后端分离 + token 认证，不需要 CSRF 防护
                .csrf(c -> c.disable())
                // 允许跨域（开发时前端 5173 端口直连后端）
                .cors(c -> c.configurationSource(cors()))
                // 不创建 Session
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        // 流式响应结束时 Tomcat 会做一次 ASYNC 内部分发，此时已无登录信息，必须放行，
                        // 否则会触发“Access Denied”导致连接中断、前端显示“请求失败”
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        // 登录接口无需认证
                        .requestMatchers("/api/auth/**").permitAll()
                        // 浏览器跨域预检请求放行
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 其余接口都需要登录
                        .anyRequest().authenticated())
                // 未登录时返回 401 JSON，而不是跳转登录页
                .exceptionHandling(e -> e.authenticationEntryPoint((req, res, ex) -> {
                    res.setStatus(401);
                    res.setContentType("application/json;charset=UTF-8");
                    res.getWriter().write("{\"error\":\"未登录或登录已过期\"}");
                }))
                // 在用户名密码过滤器之前插入 JWT 过滤器
                .addFilterBefore(jwtFilter(jwt), UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    /**
     * JWT 认证过滤器：每个请求执行一次。
     * 请求头形如 "Authorization: Bearer xxx"，解析成功就把用户身份写入安全上下文；
     * 解析失败（伪造或过期）则不设置身份，后续会被判定为未登录。
     */
    private OncePerRequestFilter jwtFilter(JwtUtil jwt) {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws ServletException, IOException {
                String header = req.getHeader("Authorization");
                if (header != null && header.startsWith("Bearer ")) {
                    try {
                        Claims claims = jwt.parse(header.substring(7));
                        var auth = new UsernamePasswordAuthenticationToken(
                                claims.getSubject(),          // 用户名
                                claims.get("uid"),            // 用户 ID
                                List.of(new SimpleGrantedAuthority("ROLE_" + claims.get("role"))));
                        auth.setDetails(claims);              // 供 PermissionService 校验 bv
                        SecurityContextHolder.getContext().setAuthentication(auth);
                    } catch (Exception ignored) {
                        // token 无效：不设置身份，交给后续流程返回 401
                    }
                }
                chain.doFilter(req, res);
            }
        };
    }

    /** 跨域配置：来源由 kb.cors.allowed-origins 决定，不在列表中的来源浏览器会拒绝跨域请求 */
    CorsConfigurationSource cors() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = java.util.Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        config.setAllowedOriginPatterns(origins.isEmpty() ? List.of("*") : origins);
        config.setAllowedMethods(List.of("*"));
        config.setAllowedHeaders(List.of("*"));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
