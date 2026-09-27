package io.github.theword.queqiao.core.support;

import io.github.theword.queqiao.core.api.platform.PlayerMessageSender;
import io.github.theword.queqiao.core.api.platform.PlayerProvider;
import io.github.theword.queqiao.core.event.model.PlayerModel;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 平台玩家能力测试替身
 *
 * <p>用于在不触碰 Minecraft、不触碰 WebSocket 的前提下验证 Core 的
 * 私聊业务流程（查找 → 找不到 / 发送 → 响应）。
 *
 * <p>这两个替身刻意只实现平台 SPI 的<b>原子能力</b>，不含任何查找逻辑——
 * 查找规则属于 Core，这正是本实验要证明的分工。
 */
public final class PlayerStubs {

    private PlayerStubs() {
    }

    /**
     * 返回固定玩家列表的 {@link PlayerProvider}
     */
    public static final class FakePlayerProvider implements PlayerProvider {

        private final List<PlayerModel> players;
        private final AtomicInteger callCount = new AtomicInteger();

        public FakePlayerProvider(PlayerModel... players) {
            this.players = players == null ? Collections.<PlayerModel>emptyList() : Arrays.asList(players);
        }

        @Override
        public List<PlayerModel> getOnlinePlayers() {
            callCount.incrementAndGet();
            // 返回快照：实现不得暴露内部可变集合
            return new ArrayList<>(players);
        }

        /**
         * @return 被调用次数，用于断言"每次请求只查一次玩家"
         */
        public int getCallCount() {
            return callCount.get();
        }
    }

    /**
     * 记录收到的目标玩家与消息的 {@link PlayerMessageSender}
     */
    public static final class RecordingPlayerMessageSender implements PlayerMessageSender {

        private final List<PlayerModel> targets = Collections.synchronizedList(new ArrayList<>());
        private final List<JsonElement> messages = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void sendPrivateMessage(PlayerModel player, JsonElement message) {
            targets.add(player);
            messages.add(message);
        }

        public int getSendCount() {
            synchronized (targets) {
                return targets.size();
            }
        }

        public List<PlayerModel> getTargets() {
            synchronized (targets) {
                return new ArrayList<>(targets);
            }
        }

        public List<JsonElement> getMessages() {
            synchronized (messages) {
                return new ArrayList<>(messages);
            }
        }

        /**
         * @return 最近一次发送的目标玩家；未发送过时为 null
         */
        public PlayerModel getLastTarget() {
            synchronized (targets) {
                return targets.isEmpty() ? null : targets.get(targets.size() - 1);
            }
        }
    }
}
