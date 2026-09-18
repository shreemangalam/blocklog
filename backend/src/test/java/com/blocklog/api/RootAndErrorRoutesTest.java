package com.blocklog.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The backend used to fall back to Spring Boot's Whitelabel Error Page on
 * anything not under {@code /api/**}. That was an "organization" gap for
 * anyone browsing to the root or fat-fingering a URL. This pins:
 *
 * 1. GET / returns 200 with a JSON manifest of the API surface.
 * 2. GET on an unknown path returns 404 with a small JSON body carrying
 *    status, error phrase, path, and a hint. No "Whitelabel" text anywhere.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RootAndErrorRoutesTest {

    @TempDir
    static Path tempDataDir;

    @DynamicPropertySource
    static void engineProperties(DynamicPropertyRegistry registry) {
        registry.add("blocklog.data-dir", () -> tempDataDir.toString());
    }

    @Autowired
    TestRestTemplate rest;

    @Test
    @SuppressWarnings("rawtypes")
    void rootReturnsApiManifest() {
        ResponseEntity<java.util.Map> resp = rest.getForEntity("/", java.util.Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        java.util.Map body = resp.getBody();
        assertNotNull(body);
        assertEquals("blocklog", body.get("service"));
        assertNotNull(body.get("endpoints"));
        assertTrue(body.get("endpoints") instanceof java.util.List<?> list && !list.isEmpty(),
                "endpoints list must not be empty");
    }

    @Test
    void unknownJsonPathReturnsOrganizedError() {
        HttpHeaders acceptJson = new HttpHeaders();
        acceptJson.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> resp = rest.exchange(
                "/no-such-thing", HttpMethod.GET, new HttpEntity<Void>(acceptJson), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        String body = resp.getBody();
        assertNotNull(body);
        assertFalse(body.toLowerCase().contains("whitelabel"),
                "no whitelabel error page should ever be returned; body was: " + body);
        assertTrue(body.contains("\"status\":404"), "body should carry status code");
        assertTrue(body.contains("\"path\""), "body should carry the request path");
    }

    @Test
    void unknownHtmlPathReturnsBrandedHtml() {
        HttpHeaders acceptHtml = new HttpHeaders();
        acceptHtml.setAccept(java.util.List.of(MediaType.TEXT_HTML));
        ResponseEntity<String> resp = rest.exchange(
                "/no-such-thing", HttpMethod.GET, new HttpEntity<Void>(acceptHtml), String.class);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        String body = resp.getBody();
        assertNotNull(body);
        assertFalse(body.toLowerCase().contains("whitelabel"),
                "no whitelabel error page in the HTML variant either; body was: " + body);
        assertTrue(body.contains("BlockLog"), "branded HTML page must mention BlockLog");
        assertTrue(body.contains("/api/v1/logs"), "HTML must point at the real endpoints");
    }
}
