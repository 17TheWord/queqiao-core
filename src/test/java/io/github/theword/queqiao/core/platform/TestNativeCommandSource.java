package io.github.theword.queqiao.core.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestNativeCommandSource {

    private static final Logger log = LoggerFactory.getLogger(TestNativeCommandSource.class);

    public boolean hasPermission(String permission) {
        if (permission.equals("permission")) {
            return true;
        }
        return false;
    }

    public void sendMessage(TestComponent component) {
        log.info("Message sent to command source: {}", component.getJson());
    }

}
