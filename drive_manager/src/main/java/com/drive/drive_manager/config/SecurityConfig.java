package com.drive.drive_manager.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.stream.Stream;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Value("${cors.allowed-origins}")
    private List<String> allowedOrigins;

    /** The Android app's WebView (Capacitor serves the page at https://localhost): always allowed, whatever the environment lists. */
    @Value("${cors.app-origins:https://localhost}")
    private List<String> appOrigins;

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // The whole site needs a session: only the deploy health check, the Drive webhook
                        // (validated by its channel token), the idle check and the simulator socket are open.
                        .requestMatchers(HttpMethod.GET, "/api/drive/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/drive/webhook").permitAll()
                        // A server that sleeps asks it to know whether anybody is still using the site
                        .requestMatchers(HttpMethod.GET, "/api/system/activity").permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        .anyRequest().authenticated()
                )
                // No valid session (expired or missing token): 401, so the site signs out; 403 stays for a valid session without the permission
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        return corsSource(allowedOrigins, appOrigins);
    }

    /**
     * /api/** for the configured origins plus the Android app's. The site's own pages are same-origin (the proxy's
     * forwarded headers, server.forward-headers-strategy) and never need it. The app sends its session as a bearer
     * token (no cookies), so allowing its origin lets nothing reach the API without one.
     */
    static CorsConfigurationSource corsSource(List<String> allowedOrigins, List<String> appOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Stream.concat(allowedOrigins.stream(), appOrigins.stream())
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .distinct()
                .toList());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
