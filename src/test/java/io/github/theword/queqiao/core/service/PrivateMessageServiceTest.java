package io.github.theword.queqiao.core.service;

import io.github.theword.queqiao.core.event.model.PlayerModel;
import io.github.theword.queqiao.core.response.PrivateMessageResponse;
import io.github.theword.queqiao.core.support.PlayerStubs;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PrivateMessageService} 单元测试
 *
 * <p>只使用平台替身，不触碰 Minecraft、不触碰 WebSocket。
 *
 * <p><b>最关键的一条断言</b>：{@code PlayerMessageSender} 收到的目标玩家
 * 必须是 Core 查找出来的那一个（identity 相同），
 * 而不是让平台再查一次——这正是"消除平台重复查找代码"的落地证明。
 */
class PrivateMessageServiceTest {

    private static final UUID STEVE_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ALEX_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID UNKNOWN_UUID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    private static final JsonElement MESSAGE = new JsonPrimitive("hi");

    @Test
    @DisplayName("UUID 命中：发送给查找出的玩家，并返回成功响应")
    void uuidHitSendsToLookedUpPlayer() {
        PlayerModel steve = new PlayerModel("Steve", STEVE_UUID);
        PlayerModel alex = new PlayerModel("Alex", ALEX_UUID);
        PlayerStubs.FakePlayerProvider provider = new PlayerStubs.FakePlayerProvider(steve, alex);
        PlayerStubs.RecordingPlayerMessageSender sender = new PlayerStubs.RecordingPlayerMessageSender();

        PrivateMessageResponse response = new PrivateMessageService(provider, sender)
                .send(null, STEVE_UUID, MESSAGE);

        assertEquals(1, sender.getSendCount(), "应发送一次");
        assertSame(steve, sender.getLastTarget(), "收到的必须是 Core 查找出的同一个 PlayerModel 实例");
        assertEquals(MESSAGE, sender.getMessages().get(0), "消息应原样传递");
        assertSame(steve, response.getPlayer(), "响应中的目标玩家应为查找结果");
        assertNotNull(response.getMessage());
        assertEquals(1, provider.getCallCount(), "每次私聊只应查询一次在线玩家");
    }

    @Test
    @DisplayName("UUID 不命中 + 昵称命中：回退到昵称并发送")
    void uuidMissThenNicknameHit() {
        PlayerModel alex = new PlayerModel("Alex", ALEX_UUID);
        PlayerStubs.FakePlayerProvider provider = new PlayerStubs.FakePlayerProvider(alex);
        PlayerStubs.RecordingPlayerMessageSender sender = new PlayerStubs.RecordingPlayerMessageSender();

        PrivateMessageResponse response = new PrivateMessageService(provider, sender)
                .send("Alex", UNKNOWN_UUID, MESSAGE);

        assertEquals(1, sender.getSendCount());
        assertSame(alex, sender.getLastTarget());
        assertSame(alex, response.getPlayer());
    }

    @Test
    @DisplayName("只给昵称：按昵称命中并发送（含两侧空白）")
    void nicknameHit() {
        PlayerModel alex = new PlayerModel("Alex", ALEX_UUID);
        PlayerStubs.FakePlayerProvider provider = new PlayerStubs.FakePlayerProvider(alex);
        PlayerStubs.RecordingPlayerMessageSender sender = new PlayerStubs.RecordingPlayerMessageSender();

        PrivateMessageResponse response = new PrivateMessageService(provider, sender)
                .send("  Alex  ", null, MESSAGE);

        assertEquals(1, sender.getSendCount());
        assertSame(alex, sender.getLastTarget());
        assertSame(alex, response.getPlayer());
    }

    @Test
    @DisplayName("找不到目标：返回 playerNotFound，且不发送任何消息")
    void notFoundDoesNotSend() {
        PlayerModel alex = new PlayerModel("Alex", ALEX_UUID);
        PlayerStubs.FakePlayerProvider provider = new PlayerStubs.FakePlayerProvider(alex);
        PlayerStubs.RecordingPlayerMessageSender sender = new PlayerStubs.RecordingPlayerMessageSender();

        PrivateMessageResponse response = new PrivateMessageService(provider, sender)
                .send("Nobody", UNKNOWN_UUID, MESSAGE);

        assertEquals(0, sender.getSendCount(), "找不到目标时不得发送");
        assertNull(response.getPlayer(), "未找到时响应不应带目标玩家");
        assertNotNull(response.getMessage());
    }

    @Test
    @DisplayName("uuid 与昵称均无效：返回 playerNotFound，且不发送")
    void bothEmptyDoesNotSend() {
        PlayerStubs.FakePlayerProvider provider = new PlayerStubs.FakePlayerProvider(
                new PlayerModel("Alex", ALEX_UUID));
        PlayerStubs.RecordingPlayerMessageSender sender = new PlayerStubs.RecordingPlayerMessageSender();

        PrivateMessageResponse response = new PrivateMessageService(provider, sender).send(null, null, MESSAGE);

        assertEquals(0, sender.getSendCount());
        assertNull(response.getPlayer());
    }

    @Test
    @DisplayName("构造器拒绝 null 平台能力")
    void constructorRejectsNulls() {
        PlayerStubs.FakePlayerProvider provider = new PlayerStubs.FakePlayerProvider();
        PlayerStubs.RecordingPlayerMessageSender sender = new PlayerStubs.RecordingPlayerMessageSender();

        assertThrows(NullPointerException.class, () -> new PrivateMessageService(null, sender));
        assertThrows(NullPointerException.class, () -> new PrivateMessageService(provider, null));
    }
}
