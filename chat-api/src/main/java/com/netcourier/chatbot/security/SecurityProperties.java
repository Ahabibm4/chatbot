package com.netcourier.chatbot.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat.security")
public class SecurityProperties {

    /**
     * Optional static bearer token used to authenticate requests when configured.
     */
    private String staticToken;

    public String getStaticToken() {
        return staticToken;
    }

    public void setStaticToken(String staticToken) {
        this.staticToken = staticToken;
    }

    public boolean hasStaticToken() {
        return staticToken != null && !staticToken.isBlank();
    }
}
