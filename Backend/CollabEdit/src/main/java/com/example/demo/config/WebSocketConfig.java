package com.example.demo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic"); // server sends messages here
        config.setApplicationDestinationPrefixes("/app"); // client sends messages here
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws") // endpoint clients connect to
                .setAllowedOriginPatterns("*");
                // Spring’s WebSocket origin check validates the browser’s `Origin` header against allowed patterns. Even behind Nginx, this header still represents the client origin, not an internal proxy address, since proxies typically forward it unchanged. In this setup, access control is primarily enforced by network topology (backend not publicly exposed and reachable only via Nginx). The origin check is therefore redundant in normal deployments but can still act as a secondary safeguard if the backend is ever exposed directly or infrastructure changes.

    }
}
