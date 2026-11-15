package com.processVisualisation.virtualKitchen.auth.security;

import com.processVisualisation.virtualKitchen.ai.recipe.narration.storage.FileSystemNarrationAudioStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Configures Spring Security for the application: password hashing, CORS,
 * CSRF, JWT-based request authentication, and which request paths are public, need a signed-in user, or need
 * an ADMIN.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final boolean apiDocsPublic;

    /**
     * Creates this configuration.
     *
     * @param jwtAuthenticationFilter filter that resolves the authenticated
     *                                user from the request's JWT before
     *                                Spring's default authentication filter runs
     * @param apiDocsPublic           whether Swagger UI / OpenAPI docs are reachable without signing in
     *                                ({@code app.security.api-docs-public}, default true; set false in production)
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          @Value("${app.security.api-docs-public:true}") boolean apiDocsPublic) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.apiDocsPublic = apiDocsPublic;
    }

    /**
     * Provides the password encoder used to hash and verify user passwords.
     *
     * @return a {@link BCryptPasswordEncoder} instance
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Builds the application's {@link SecurityFilterChain}. Every request needs a valid JWT except the few
     * signed-out entry points below; identity always comes from the token (see {@link CurrentUser}), never from
     * request parameters. Rules match first-declared-wins, so specific rules come before broader ones:
     * <ul>
     *   <li>public — sign-up / log-in / OTP / password-reset ({@code POST /api/v1/auth/**}, but not
     *       {@code /me}), narration audio files (played by {@code <audio>}, which can't send a token; their
     *       names are unguessable UUIDs), CORS preflights, the error page, and — when
     *       {@code app.security.api-docs-public} is true — Swagger / OpenAPI docs;</li>
     *   <li>ADMIN — {@code /api/v1/admin/**}, plus back-office endpoints the product UI never calls: catalog,
     *       kitchen and inventory writes, item costs, raw kitchen-inventory links, user management and the
     *       {@code /api/ai/**} chat / response tools;</li>
     *   <li>any signed-in user — everything else. Endpoints that name a user, kitchen or recipe in the path check
     *       ownership themselves and answer 403 for someone else's.</li>
     * </ul>
     * Unauthenticated requests get a bare 401 (no HTTP Basic challenge).
     *
     * @param http the {@link HttpSecurity} builder to configure
     * @return the configured {@link SecurityFilterChain}
     * @throws Exception if the security chain fails to build
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 1. Enable CORS for the Vite frontend
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))

                // 2. Disable CSRF: the API is authenticated by a bearer token, not cookies
                .csrf(csrf -> csrf.disable())

                // 3. Authorize requests (first match wins)
                .authorizeHttpRequests(auth -> {
                    auth
                            // Signed-out entry points
                            .requestMatchers("/error").permitAll()
                            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                            .requestMatchers("/api/v1/auth/me").authenticated()
                            .requestMatchers(HttpMethod.POST, "/api/v1/auth/**").permitAll()
                            .requestMatchers(HttpMethod.GET, FileSystemNarrationAudioStorage.URL_PATH + "**").permitAll();
                    if (apiDocsPublic) {
                        auth.requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll();
                    }
                    auth
                            // Admin area
                            .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                            // Back-office endpoints with no place in the product UI
                            .requestMatchers("/api/ai/**").hasRole("ADMIN")
                            .requestMatchers("/api/v1/item-cost/**", "/api/v1/kitchen-inventory/**").hasRole("ADMIN")
                            .requestMatchers(HttpMethod.POST, "/api/v1/ingredients/**", "/api/v1/equipments/**",
                                    "/api/v1/kitchens/**", "/api/v1/inventory/**").hasRole("ADMIN")
                            .requestMatchers(HttpMethod.PUT, "/api/v1/ingredients/**", "/api/v1/equipments/**",
                                    "/api/v1/kitchens/**", "/api/v1/inventory/**").hasRole("ADMIN")
                            .requestMatchers(HttpMethod.DELETE, "/api/v1/ingredients/**", "/api/v1/equipments/**",
                                    "/api/v1/kitchens/**", "/api/v1/inventory/**").hasRole("ADMIN")
                            // A user's own settings; managing other users is for admins
                            .requestMatchers("/api/v1/users/me/**").authenticated()
                            .requestMatchers("/api/v1/users/**").hasRole("ADMIN")
                            // Everything else: any signed-in user (ownership is checked per endpoint)
                            .anyRequest().authenticated();
                })

                // 4. Resolve the authenticated user (if any) from the JWT before Spring's default auth filter runs
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)

                // 5. Answer unauthenticated requests with a plain 401. Not HTTP Basic: its
                // WWW-Authenticate challenge pops a browser login (e.g. from Swagger) that no
                // real account can pass, since users authenticate only via JWT.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));

        return http.build();
    }

    /**
     * Defines the CORS policy applied to every endpoint ({@code /**}):
     * allows the local Vite dev frontend origins ({@code http://localhost:5173}
     * and {@code http://127.0.0.1:5173}), the GET/POST/PUT/PATCH/DELETE/OPTIONS
     * methods, any request header, and credentialed (cookie/auth-header) requests.
     *
     * @return the {@link CorsConfigurationSource} used by {@link #securityFilterChain}
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of("http://localhost:5173", "http://127.0.0.1:5173"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setExposedHeaders(List.of("X-Request-ID"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}

