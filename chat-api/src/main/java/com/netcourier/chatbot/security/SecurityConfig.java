package com.netcourier.chatbot.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.server.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.AuthenticationWebFilter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private final JwtRoleConverter roleConverter;
    private final SecurityProperties securityProperties;

    public SecurityConfig(JwtRoleConverter roleConverter, SecurityProperties securityProperties) {
        this.roleConverter = roleConverter;
        this.securityProperties = securityProperties;
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        ServerHttpSecurity security = http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(registry -> registry
                        .pathMatchers("/actuator/**", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .anyExchange().authenticated());

        if (securityProperties.hasStaticToken()) {
            security.addFilterAt(staticTokenAuthenticationFilter(), SecurityWebFiltersOrder.AUTHENTICATION);
            return security.build();
        }

        return security
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwtSpec -> jwtSpec.jwtAuthenticationConverter(this::convertJwt)))
                .build();
    }

    private Mono<AbstractAuthenticationToken> convertJwt(Jwt jwt) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roleConverter);
        return Mono.justOrEmpty(converter.convert(jwt));
    }

    private AuthenticationWebFilter staticTokenAuthenticationFilter() {
        AuthenticationWebFilter filter = new AuthenticationWebFilter(new StaticTokenAuthenticationManager(securityProperties.getStaticToken()));
        filter.setServerAuthenticationConverter(new ServerBearerTokenAuthenticationConverter());
        filter.setRequiresAuthenticationMatcher(ServerWebExchangeMatchers.anyExchange());
        filter.setSecurityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        return filter;
    }
}
