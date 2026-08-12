package com.techconnect.config;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;

/**
 * Sets the WebSocket/SockJS handler mapping order to -1 after the Spring
 * context finishes refreshing, giving it priority over
 * RequestMappingHandlerMapping (order 0) which contains the StaticController
 * catch-all /** pattern.  Without this, SockJS's /ws/info probe is intercepted
 * by the StaticController and receives a 404 before the WebSocket handler sees it.
 */
@Configuration
public class WebSocketOrderConfig implements ApplicationListener<ContextRefreshedEvent> {

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        ApplicationContext ctx = event.getApplicationContext();
        // The bean name used by Spring's WebSocket/STOMP infrastructure
        for (String name : new String[]{"webSocketHandlerMapping", "stompWebSocketHandlerMapping"}) {
            try {
                Object bean = ctx.getBean(name);
                if (bean instanceof AbstractHandlerMapping mapping) {
                    mapping.setOrder(-1);
                }
            } catch (Exception ignored) { /* bean may not exist under this name */ }
        }
    }
}
