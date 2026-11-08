package com.drive.drive_manager.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The API's CORS (the same source Spring Security's CorsFilter uses): the configured origins plus the Android app's. */
class CorsOriginsTest {

    @RestController
    static class Api {
        @GetMapping("/api/drive/decklists")
        String decks() {
            return "[]";
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Api())
            .addFilters(new CorsFilter(SecurityConfig.corsSource(
                    List.of("https://erastcg.com", "https://www.erastcg.com", " "),
                    List.of("https://localhost"))))
            .build();

    private MockMvc preflight(String origin, int expected) throws Exception {
        mvc.perform(options("/api/drive/decklists")
                        .header(HttpHeaders.ORIGIN, origin)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().is(expected));
        return mvc;
    }

    @Test
    void theAndroidAppsOriginIsAllowed() throws Exception {
        preflight("https://localhost", 200);
        mvc.perform(options("/api/drive/decklists")
                        .header(HttpHeaders.ORIGIN, "https://localhost")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://localhost"));
        mvc.perform(get("/api/drive/decklists").header(HttpHeaders.ORIGIN, "https://localhost"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://localhost"));
    }

    @Test
    void theSitesOriginsStillAre() throws Exception {
        preflight("https://www.erastcg.com", 200);
        preflight("https://erastcg.com", 200);
    }

    @Test
    void anyOtherOriginIsRefused() throws Exception {
        preflight("https://evil.example", 403);
        // http://localhost would be MockMvc's own origin (not a CORS request): another port is not
        preflight("http://localhost:4200", 403);
        preflight("https://localhost.evil.example", 403);
        preflight("https://localhost:8443", 403);
        mvc.perform(get("/api/drive/decklists").header(HttpHeaders.ORIGIN, "https://evil.example"))
                .andExpect(status().isForbidden());
    }
}
