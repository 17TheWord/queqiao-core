package io.github.theword.queqiao.core.platform;

import java.util.Arrays;
import java.util.Collection;
import java.util.UUID;

import com.google.gson.JsonElement;

import io.github.theword.queqiao.core.constant.ServerTypeConstant;
import io.github.theword.queqiao.core.event.model.PlayerModel;

public class PlatformContext extends AbstractPlatformContext<TestServer, TestComponent, TestPlayer> {

    public PlatformContext(TestServer server) {
        super(server);
    }

    @Override
    public String getServerType() {
        return ServerTypeConstant.SPIGOT;
    }

    @Override
    public String getServerVersion() {
        return "1.20.1";
    }

    @Override
    public TestComponent jsonToComponent(JsonElement jsonElement) {
        return new TestComponent(jsonElement);
    }

    @Override
    public Collection<TestPlayer> getPlayers() {
        TestPlayer player1 = new TestPlayer("Player1", UUID.randomUUID());
        TestPlayer player2 = new TestPlayer("Player2", UUID.randomUUID());
        return Arrays.asList(player1, player2);
    }

    @Override
    public String getPlayerName(TestPlayer player) {
        return player.getName();
    }

    @Override
    public UUID getPlayerUUID(TestPlayer player) {
        return player.getUuid();
    }

    @Override
    public PlayerModel getPlayerModel(TestPlayer player) {
        PlayerModel playerModel = new PlayerModel();
        playerModel.setNickname(player.getName());
        playerModel.setUuid(player.getUuid());
        return playerModel;
    }

    @Override
    public PlatformResult<Void> broadcast(TestComponent component) {
        server.broadcast(component);
        return PlatformResult.success(null);
    }

    @Override
    public PlatformResult<Void> sendPrivateMessage(TestPlayer player, TestComponent component) {
        player.sendMessage(component);
        return PlatformResult.success(null);
    }

}
