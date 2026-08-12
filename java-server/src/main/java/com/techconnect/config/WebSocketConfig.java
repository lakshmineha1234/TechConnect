package com.techconnect.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.HttpSessionHandshakeInterceptor;

import java.util.Map;

/**
 * Configures STOMP over WebSocket (SockJS fallback).
 *
 * Flow:
 *  1. Client connects to /ws  (SockJS)
 *  2. HttpSessionHandshakeInterceptor copies the HTTP session attributes
 *     (including "userId") into the WebSocket session attributes.
 *  3. The ChannelInterceptor sets a Principal whose name = userId,
 *     enabling user-specific message routing (/user/queue/chat).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // In-memory broker for user-specific queues
        config.enableSimpleBroker("/queue", "/topic");
        // Prefix for @MessageMapping methods
        config.setApplicationDestinationPrefixes("/app");
        // Prefix for user-specific destinations (/user/{id}/queue/...)
        config.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                // Copy HTTP session attributes → WebSocket session attributes
                .addInterceptors(new HttpSessionHandshakeInterceptor())
                .withSockJS();
    }

    /**
     * On CONNECT frame: read userId from WebSocket session attributes
     * (originally from the HTTP session) and set it as the Principal.
     * Spring then uses this name to route /user/queue/... messages.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor =
                        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
                    Map<String, Object> attrs = accessor.getSessionAttributes();
                    if (attrs != null) {
                        String userId = (String) attrs.get("userId");
                        if (userId != null) {
                            accessor.setUser(() -> userId);
                        }
                    }
                }
                return message;
            }
        });
    }
}
