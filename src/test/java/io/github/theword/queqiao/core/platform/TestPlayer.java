package io.github.theword.queqiao.core.platform;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestPlayer extends TestCommandSource {

    private static final Logger log = LoggerFactory.getLogger(TestPlayer.class);

    private final String name;
    private final UUID uuid;

    public TestPlayer(String name, UUID uuid) {
        this.name = name;
        this.uuid = uuid;
    }

    public String getName() {
        return name;
    }

    public UUID getUuid() {
        return uuid;
    }

    public void sendMessage(TestComponent component) {
        log.info("Message sent to player {}: {}", name, component.getJson());
    }

}
