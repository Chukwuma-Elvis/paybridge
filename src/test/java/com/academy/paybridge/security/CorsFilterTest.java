package com.academy.paybridge.security;

import com.academy.paybridge.security.service.SecurityProperties;
import com.academy.paybridge.security.web.CorsFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CorsFilterTest {

    @Test
    void allowedOriginGetsCorsHeadersAnd200OnOptionsPreflight() throws ServletException, IOException {
        SecurityProperties.Cors cors = new SecurityProperties.Cors("https://paybridge-frontend.vercel.app,http://localhost:5173");
        CorsFilter filter = new CorsFilter(cors);

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/customers");
        request.addHeader("Origin", "https://paybridge-frontend.vercel.app");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://paybridge-frontend.vercel.app");
        assertThat(response.getHeader("Vary")).isEqualTo("Origin");
        assertThat(response.getHeader("Access-Control-Allow-Methods")).contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
        assertThat(response.getHeader("Access-Control-Allow-Headers")).contains("X-API-Key", "Idempotency-Key", "X-Request-Id");
        assertThat(response.getHeader("Access-Control-Expose-Headers")).contains("X-Request-Id", "Retry-After");
        verifyNoInteractions(chain); // preflight must not hit downstream filters
    }

    @Test
    void unallowedOriginGetsNoCorsHeadersAndPassesDownstream() throws ServletException, IOException {
        SecurityProperties.Cors cors = new SecurityProperties.Cors("https://paybridge-frontend.vercel.app");
        CorsFilter filter = new CorsFilter(cors);

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/customers");
        request.addHeader("Origin", "https://evil.com");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void emptyAllowListPreservesDenyByDefault() throws ServletException, IOException {
        SecurityProperties.Cors cors = new SecurityProperties.Cors("");
        CorsFilter filter = new CorsFilter(cors);

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/customers");
        request.addHeader("Origin", "http://localhost:5173");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isNull();
        verify(chain).doFilter(request, response);
    }

    @Test
    void realRequestFromAllowedOriginGetsHeadersAndPassesDownstream() throws ServletException, IOException {
        SecurityProperties.Cors cors = new SecurityProperties.Cors("http://localhost:5173");
        CorsFilter filter = new CorsFilter(cors);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/customers");
        request.addHeader("Origin", "http://localhost:5173");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:5173");
        assertThat(response.getHeader("Access-Control-Expose-Headers")).contains("X-Request-Id", "Retry-After");
        verify(chain).doFilter(request, response); // actual requests proceed to authentication
    }

    @Test
    void originMatchingToleratesTrailingSlashesAndWildcardSuffix() throws ServletException, IOException {
        SecurityProperties.Cors cors = new SecurityProperties.Cors("https://paybridge-frontend-chi.vercel.app/,*.vercel.app");
        CorsFilter filter = new CorsFilter(cors);

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/actuator/health");
        request.addHeader("Origin", "https://paybridge-frontend-chi.vercel.app");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://paybridge-frontend-chi.vercel.app");
    }
}
