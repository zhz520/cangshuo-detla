package com.delta.admin;

import com.delta.common.websocket.manager.WebSocketSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatSessionManagerTest {
    @Test
    void anOldSocketCannotRemoveTheReplacementConnection() {
        WebSocketSessionManager manager = new WebSocketSessionManager();
        WebSocketSession first = mock(WebSocketSession.class);
        WebSocketSession second = mock(WebSocketSession.class);

        assertThat(manager.add("USER:1", first)).isNull();
        assertThat(manager.add("USER:1", second)).isSameAs(first);
        manager.remove("USER:1", first);
        assertThat(manager.get("USER:1")).isSameAs(second);
        manager.remove("USER:1", second);
        assertThat(manager.get("USER:1")).isNull();
    }
}
