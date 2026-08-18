package com.hydrapay.ledger.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final CorrelationIdFilter correlationIdFilter;
    private final RateLimitingFilter rateLimitingFilter;

    @Value("${hydrapay.security.enabled:true}")
    private boolean securityEnabled = true;

    @Value("${hydrapay.security.allowed-origins:http://localhost:3000}")
    private List<String> allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        UserDetails admin = User.builder()
                .username("admin")
                .password(encoder.encode("adminpass"))
                .roles("ADMIN")
                .build();

        UserDetails operator = User.builder()
                .username("operator")
                .password(encoder.encode("operatorpass"))
                .roles("OPERATOR")
                .build();

        UserDetails reader = User.builder()
                .username("reader")
                .password(encoder.encode("readerpass"))
                .roles("READONLY")
                .build();

        return new InMemoryUserDetailsManager(admin, operator, reader);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(correlationIdFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(rateLimitingFilter, CorrelationIdFilter.class);

        if (!securityEnabled) {
            http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }

        http
            .httpBasic(Customizer.withDefaults())
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, authEx) -> {
                    res.setStatus(jakarta.servlet.http.HttpServletResponse.SC_UNAUTHORIZED);
                    res.setContentType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
                    String cid = org.slf4j.MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
                    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("timestamp", java.time.OffsetDateTime.now().toString());
                    body.put("status", 401);
                    body.put("error", "UNAUTHORIZED");
                    body.put("message", "Authentication credentials required or invalid.");
                    body.put("path", req.getRequestURI());
                    body.put("correlationId", cid != null ? cid : "N/A");
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValue(res.getWriter(), body);
                })
                .accessDeniedHandler((req, res, accessEx) -> {
                    res.setStatus(jakarta.servlet.http.HttpServletResponse.SC_FORBIDDEN);
                    res.setContentType(org.springframework.http.MediaType.APPLICATION_JSON_VALUE);
                    String cid = org.slf4j.MDC.get(CorrelationIdFilter.MDC_CORRELATION_ID_KEY);
                    java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
                    body.put("timestamp", java.time.OffsetDateTime.now().toString());
                    body.put("status", 403);
                    body.put("error", "FORBIDDEN");
                    body.put("message", "Access denied. Insufficient role permissions.");
                    body.put("path", req.getRequestURI());
                    body.put("correlationId", cid != null ? cid : "N/A");
                    new com.fasterxml.jackson.databind.ObjectMapper().writeValue(res.getWriter(), body);
                })
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/transfers").hasAnyRole("OPERATOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/reconcile").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/v1/**").hasAnyRole("READONLY", "OPERATOR", "ADMIN")
                .anyRequest().authenticated()
            );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Cache-Control", "Content-Type", "X-Idempotency-Key", "X-Correlation-ID"));
        config.setExposedHeaders(List.of("X-Correlation-ID"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
