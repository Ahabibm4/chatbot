package com.netcourier.chatbot.telemetry;

import io.micrometer.tracing.SpanCustomizer;
import io.micrometer.tracing.Tracer;
import org.springframework.boot.actuate.autoconfigure.tracing.ConditionalOnEnabledTracing;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnEnabledTracing
public class TracingConfig {

    @Bean
    public SpanCustomizer spanCustomizer(Tracer tracer) {
        return tracer.currentSpanCustomizer();
    }
}
