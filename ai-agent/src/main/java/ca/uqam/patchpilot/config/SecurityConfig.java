package ca.uqam.patchpilot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF: this is a stateless REST API. JWTs and HMAC
            // signatures protect against cross-site request forgery natively.
            .csrf(csrf -> csrf.disable())

            // CORS: Angular dev server (4200) and Azure Static Web Apps both
            // need to reach the AI Agent. Configured in corsConfigurationSource().
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))

            // No server-side session: every request is authenticated by its JWT.
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

            .authorizeHttpRequests(auth -> auth
                // SonarQube webhook — no JWT (SonarQube doesn't use Auth0).
                // Security is provided by the HMAC-SHA256 signature check
                // in WebhookController, not by Spring Security.
                .requestMatchers(HttpMethod.POST, "/api/webhooks/sonarqube").permitAll()

                // Docker Compose healthcheck and Prometheus scrape — no JWT.
                // Both are on the internal Docker network; no public exposure.
                .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()

                // All other endpoints require a valid Auth0 JWT.
                .anyRequest().authenticated()
            )

            // Validate Auth0 JWTs. Spring auto-fetches the JWKS from the
            // issuer-uri configured in application.yml and verifies every
            // incoming token's signature and expiry.
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        var config = new CorsConfiguration();
        // Angular dev server + Azure Static Web Apps wildcard subdomain
        config.setAllowedOriginPatterns(List.of(
            "http://localhost:4200",
            "https://*.azurestaticapps.net"
        ));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        // Needed for the Auth0 Authorization header to be forwarded
        config.setAllowCredentials(true);

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
