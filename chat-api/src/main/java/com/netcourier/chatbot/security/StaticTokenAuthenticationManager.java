package com.netcourier.chatbot.security;

import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.server.resource.BearerTokenAuthenticationToken;
import reactor.core.publisher.Mono;

public class StaticTokenAuthenticationManager implements ReactiveAuthenticationManager {

    private final String expectedToken;

    public StaticTokenAuthenticationManager(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        if (!(authentication instanceof BearerTokenAuthenticationToken bearer)) {
            return Mono.error(new BadCredentialsException("Unsupported authentication token"));
        }

        String token = bearer.getToken();
        if (token == null || !token.equals(expectedToken)) {
            return Mono.error(new BadCredentialsException("Invalid bearer token"));
        }

        Authentication result = new UsernamePasswordAuthenticationToken(
                "static-bearer",
                null,
                AuthorityUtils.NO_AUTHORITIES
        );
        return Mono.just(result);
    }
}
