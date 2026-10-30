package com.drive.drive_manager.service;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * When someone last used the site: any API request or simulator action, except the checks that only ask whether the server
 * is up. A server that sleeps uses it to decide when nobody is left (GET /api/system/activity).
 */
@Component
public class ActivityTracker extends OncePerRequestFilter {

    /** Asked by the warm-up page and the idle check: they do not count as someone using the site. */
    private static final Set<String> NOT_ACTIVITY = Set.of("/api/drive/health", "/api/system/activity");

    private final AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
    private final Instant startedAt = Instant.now();

    public void touch() {
        lastActivity.set(System.currentTimeMillis());
    }

    public long idleSeconds() {
        return (System.currentTimeMillis() - lastActivity.get()) / 1000;
    }

    public Instant startedAt() {
        return startedAt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/") && !NOT_ACTIVITY.contains(request.getRequestURI())) touch();
        chain.doFilter(request, response);
    }
}
