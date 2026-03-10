package com.drive.drive_manager.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.util.List;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${cors.allowed-origins}")
    private List<String> allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);
    }

    /**
     * Strip trailing slashes from request URIs so that POST /api/cards/
     * is treated the same as POST /api/cards regardless of proxy behaviour.
     */
    @Bean
    public OncePerRequestFilter trailingSlashFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain)
                    throws ServletException, IOException {

                String uri = request.getRequestURI();
                if (uri.length() > 1 && uri.endsWith("/")) {
                    final String trimmed = uri.substring(0, uri.length() - 1);
                    HttpServletRequestWrapper wrapped = new HttpServletRequestWrapper(request) {
                        @Override public String getRequestURI() { return trimmed; }
                        @Override public StringBuffer getRequestURL() {
                            StringBuffer url = new StringBuffer(request.getScheme())
                                    .append("://").append(request.getServerName());
                            int port = request.getServerPort();
                            if (port != 80 && port != 443) url.append(":").append(port);
                            url.append(trimmed);
                            return url;
                        }
                    };
                    chain.doFilter(wrapped, response);
                } else {
                    chain.doFilter(request, response);
                }
            }
        };
    }
}
