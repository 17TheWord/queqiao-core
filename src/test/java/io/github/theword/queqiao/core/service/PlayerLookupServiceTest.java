package io.github.theword.queqiao.core.service;

import io.github.theword.queqiao.core.event.model.PlayerModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PlayerLookupService} 单元测试
 *
 * <p>查找规则属于 Core，因此必须由 Core 的测试锁定，平台不得自行解释。
 * 本用例不触碰 Minecraft、不触碰 WebSocket。
 */
class PlayerLookupServiceTest {

    private static final UUID STEVE_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ALEX_UUID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final PlayerLookupService service = new PlayerLookupService();

    @Test
    @DisplayName("UUID 命中：直接返回该玩家")
    void uuidHit() {
        PlayerModel steve = new PlayerModel("Steve", STEVE_UUID);
        PlayerModel alex = new PlayerModel("Alex", ALEX_UUID);

        Optional<PlayerModel> found = service.find(Arrays.asList(steve, alex), STEVE_UUID, null);

        assertTrue(found.isPresent());
        assertSame(steve, found.get());
    }

    @Test
    @DisplayName("UUID 不命中 + 昵称命中：回退到昵称查找")
    void uuidMissThenNicknameHit() {
        PlayerModel alex = new PlayerModel("Alex", ALEX_UUID);
        UUID unknownUuid = UUID.fromString("33333333-3333-3333-3333-333333333333");

        Optional<PlayerModel> found = service.find(Collections.singletonList(alex), unknownUuid, "Alex");

        assertTrue(found.isPresent(), "UUID 不命中时应继续按昵称查找");
        assertSame(alex, found.get());
    }

    @Test
    @DisplayName("只给昵称：按昵称命中")
    void nicknameOnlyHit() {
        PlayerModel alex = new PlayerModel("Alex", ALEX_UUID);

        Optional<PlayerModel> found = service.find(Collections.singletonList(alex), null, "Alex");

        assertTrue(found.isPresent());
        assertSame(alex, found.get());
    }

    @Test
    @DisplayName("昵称会先 trim，两侧空白不影响匹配")
    void nicknameIsTrimmed() {
        PlayerModel alex = new PlayerModel("Alex", ALEX_UUID);

        Optional<PlayerModel> found = service.find(Collections.singletonList(alex), null, "  Alex  ");

        assertTrue(found.isPresent());
        assertSame(alex, found.get());
    }

    @Test
    @DisplayName("大小写敏感：'alex' 不匹配 'Alex'")
    void nicknameMatchIsCaseSensitive() {
        PlayerModel alex = new PlayerModel("Alex", ALEX_UUID);

        assertFalse(
                service.find(Collections.singletonList(alex), null, "alex").isPresent(),
                "匹配规则是大小写敏感的精确匹配");
    }

    @Test
    @DisplayName("两者都不存在：返回空")
    void notFound() {
        PlayerModel alex = new PlayerModel("Alex", ALEX_UUID);
        UUID unknownUuid = UUID.fromString("44444444-4444-4444-4444-444444444444");

        assertFalse(service.find(Collections.singletonList(alex), unknownUuid, "Nobody").isPresent());
    }

    @Test
    @DisplayName("uuid 与 nickname 均为空：返回空")
    void bothEmpty() {
        PlayerModel alex = new PlayerModel("Alex", ALEX_UUID);

        assertFalse(service.find(Collections.singletonList(alex), null, null).isPresent());
        assertFalse(service.find(Collections.singletonList(alex), null, "").isPresent());
        assertFalse(service.find(Collections.singletonList(alex), null, "   ").isPresent());
    }

    @Test
    @DisplayName("多个玩家：返回正确的那一个")
    void multiplePlayers() {
        PlayerModel steve = new PlayerModel("Steve", STEVE_UUID);
        PlayerModel alex = new PlayerModel("Alex", ALEX_UUID);
        List<PlayerModel> players = Arrays.asList(steve, alex);

        assertSame(alex, service.find(players, null, "Alex").orElseThrow());
        assertSame(
                alex,
                service.find(players, ALEX_UUID, "Steve").orElseThrow(),
                "UUID 优先于昵称：UUID 命中 Alex 时，不应改按昵称返回 Steve");
        assertSame(steve, service.find(players, STEVE_UUID, null).orElseThrow());
    }

    @Test
    @DisplayName("玩家快照为 null 或空集合：返回空且不抛异常")
    void nullOrEmptyPlayers() {
        assertFalse(service.find(null, STEVE_UUID, "Steve").isPresent());
        assertFalse(service.find(Collections.<PlayerModel>emptyList(), STEVE_UUID, "Steve").isPresent());
    }

    @Test
    @DisplayName("集合含 null 元素或字段为 null 的玩家：跳过而不抛异常")
    void nullElementsAreSkipped() {
        List<PlayerModel> players = new ArrayList<>();
        players.add(null);
        players.add(new PlayerModel(null, null));
        PlayerModel steve = new PlayerModel("Steve", STEVE_UUID);
        players.add(steve);

        assertSame(steve, service.find(players, STEVE_UUID, null).orElseThrow());
        assertSame(steve, service.find(players, null, "Steve").orElseThrow());
        assertEquals(3, players.size(), "查找不应修改传入的集合");
    }
}
