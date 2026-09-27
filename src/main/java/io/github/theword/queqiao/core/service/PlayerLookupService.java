package io.github.theword.queqiao.core.service;

import io.github.theword.queqiao.core.event.model.PlayerModel;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * 玩家查找服务
 *
 * <p><b>纯逻辑</b>：不依赖任何平台类型，也不依赖 Runtime。输入是
 * "玩家快照 + uuid + nickname"，输出是"找到的目标玩家（或没找到）"。
 *
 * <p>存在的意义是消灭各平台重复实现的查找代码——
 * 此前每个平台都要自己写"遍历玩家 → 比 UUID → 比昵称 → 找不到怎么办"。
 *
 * <h2>匹配规则（唯一权威定义，平台不得自行解释）</h2>
 * <ol>
 *     <li><b>优先按 UUID</b>：当 {@code uuid != null} 时，在玩家快照中精确匹配
 *         {@link PlayerModel#getUuid()}；命中即返回。</li>
 *     <li><b>其次按昵称</b>：仅当 UUID <b>未命中</b>、且昵称有效时，才继续按昵称匹配。
 *         昵称会先 {@code trim()}；{@code trim()} 后为空视为"未提供"。
 *         与 {@link PlayerModel#getNickname()} 的比较是<b>大小写敏感的精确匹配</b>，
 *         不做前缀、模糊或大小写折叠。</li>
 *     <li><b>都不命中</b>：返回 {@link Optional#empty()}，由调用方决定如何回应。</li>
 * </ol>
 *
 * <p>边界行为：
 * <ul>
 *     <li>{@code players} 为 null 或空集合 → 返回空；</li>
 *     <li>集合中的 null 元素、以及 {@code uuid} / {@code nickname} 为 null 的玩家会被跳过，
 *         不抛异常；</li>
 *     <li>{@code uuid} 与 {@code nickname} 同时无效 → 返回空。</li>
 * </ul>
 *
 * <p>本类<b>无状态</b>，可以安全地被多线程共享。
 *
 * @since 0.7.0
 */
public final class PlayerLookupService {

    /**
     * 查找目标玩家
     *
     * @param players  玩家快照，允许为 null
     * @param uuid     目标 UUID，允许为 null
     * @param nickname 目标昵称，允许为 null（内部会 trim）
     * @return 命中的玩家；未命中返回 {@link Optional#empty()}
     */
    public Optional<PlayerModel> find(Collection<PlayerModel> players, UUID uuid, String nickname) {
        if (players == null || players.isEmpty()) {
            return Optional.empty();
        }

        if (uuid != null) {
            for (PlayerModel player : players) {
                if (player != null && uuid.equals(player.getUuid())) {
                    return Optional.of(player);
                }
            }
        }

        String normalizedNickname = nickname == null ? null : nickname.trim();
        if (normalizedNickname != null && !normalizedNickname.isEmpty()) {
            for (PlayerModel player : players) {
                if (player != null && normalizedNickname.equals(player.getNickname())) {
                    return Optional.of(player);
                }
            }
        }

        return Optional.empty();
    }
}
