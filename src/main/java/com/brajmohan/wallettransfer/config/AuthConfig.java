package com.brajmohan.wallettransfer.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Parses AUTH_TOKENS ("token:user,token:user") into a token -> user_id map.
@Configuration
public class AuthConfig {

    @Bean
    public Map<String, String> bearerTokens(@Value("${AUTH_TOKENS:demo-token:demo-user}") String raw) {
        Map<String, String> tokens = new HashMap<>();
        for (String pair : raw.split(",")) {
            String[] parts = pair.split(":", 2);
            if (parts.length == 2 && !parts[0].isBlank()) {
                tokens.put(parts[0].trim(), parts[1].trim());
            }
        }
        return tokens;
    }
}
