package com.delta.admin.chat;

import com.delta.common.chat.domain.entity.ChatSession;
import com.delta.common.chat.service.ChatAccessService;
import com.delta.common.chat.util.ChatParticipantId;
import com.delta.common.mapper.CrossModuleMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatAccessServiceTest {
    private JdbcTemplate jdbc;
    private CrossModuleMapper cross;
    private ChatAccessService access;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:chat-access-" + UUID.randomUUID() + ";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE `order`(id BIGINT PRIMARY KEY,user_id BIGINT,player_id BIGINT,status VARCHAR(32))");
        jdbc.execute("CREATE TABLE `user`(id BIGINT PRIMARY KEY,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE player(id BIGINT PRIMARY KEY,deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE admin(id BIGINT PRIMARY KEY,role VARCHAR(16),status INT,deleted INT)");
        jdbc.update("INSERT INTO `order` VALUES(1,7,3,'IN_PROGRESS'),(2,8,4,'PAID')");
        jdbc.update("INSERT INTO `user` VALUES(7,0)");
        jdbc.update("INSERT INTO player VALUES(3,0)");
        jdbc.update("INSERT INTO admin VALUES(9,'CS',1,0),(10,'ADMIN',0,0),(11,'CS',1,1)");
        cross = mock(CrossModuleMapper.class);
        access = new ChatAccessService(jdbc, cross);
    }

    @Test
    void guestContactRequiresAnActiveOrderWithThePlayer() {
        assertThat(access.canGuestContactPlayer(7L, 3L)).isTrue();
        assertThat(access.canGuestContactPlayer(7L, 4L)).isFalse();
        jdbc.update("UPDATE `order` SET status='CANCELLED' WHERE id=1");
        assertThat(access.canGuestContactPlayer(7L, 3L)).isFalse();
    }

    @Test
    void participantExistsChecksTheEntityTableAndDeletionFlag() {
        assertThat(access.participantExists(1, 7L)).isTrue();
        assertThat(access.participantExists(2, 3L)).isTrue();
        assertThat(access.participantExists(3, 9L)).isTrue();
        assertThat(access.participantExists(3, 10L)).isTrue();
        assertThat(access.participantExists(3, 11L)).isFalse();
        assertThat(access.participantExists(9, 7L)).isFalse();
    }

    @Test
    void orderParticipantsExposeUserAndOptionalPlayer() {
        ChatAccessService.OrderParticipants assigned = access.findOrderParticipants(1L);
        assertThat(assigned.userId()).isEqualTo(7L);
        assertThat(assigned.playerId()).isEqualTo(3L);

        jdbc.update("UPDATE `order` SET player_id=NULL WHERE id=2");
        ChatAccessService.OrderParticipants unassigned = access.findOrderParticipants(2L);
        assertThat(unassigned.playerId()).isNull();
        assertThat(access.findOrderParticipants(99L)).isNull();
    }

    @Test
    void randomCsFallsBackOnlyToActiveStaff() {
        assertThat(access.findRandomAvailableCsId()).isEqualTo(9L);
        jdbc.update("UPDATE admin SET status=0 WHERE id=9");
        assertThat(access.findRandomAvailableCsId()).isNull();
    }

    @Test
    void sessionEnrichmentUsesTheOtherParticipantAndUnreadCount() {
        ChatSession session = new ChatSession();
        session.setId(11L);
        session.setId1(ChatParticipantId.encodeUser(7));
        session.setId2(ChatParticipantId.encodePlayer(3));
        when(cross.selectNicknameByEncodedId(ChatParticipantId.encodePlayer(3))).thenReturn("打手A");
        when(cross.selectAvatarByEncodedId(ChatParticipantId.encodePlayer(3))).thenReturn("/avatar.png");
        when(cross.selectLastMessageType(11L)).thenReturn("TEXT");
        when(cross.selectLastMessageContent(11L)).thenReturn("你好");
        when(cross.selectUnreadCount(11L, "USER")).thenReturn(2);

        access.enrichSession(session, ChatParticipantId.encodeUser(7));

        assertThat(session.getTargetId()).isEqualTo(ChatParticipantId.encodePlayer(3));
        assertThat(session.getTargetName()).isEqualTo("打手A");
        assertThat(session.getTargetType()).isEqualTo("PLAYER");
        assertThat(session.getLastMessage()).isEqualTo("你好");
        assertThat(session.getUnreadCount()).isEqualTo(2);
    }
}
