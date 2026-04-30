package com.processVisualisation.virtualKitchen.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.Customizer;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 1. Disable CSRF so you can use POST/PUT without tokens
                .csrf(csrf -> csrf.disable())

                // 2. Authorize requests
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll() // Open Swagger
                        .anyRequest().authenticated() // Everything else needs login
                )

                // 3. Enable Basic Auth (This makes the popup login work)
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }
}

