package io.github.theword.queqiao.core.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestServer {

    private static final Logger log = LoggerFactory.getLogger(TestServer.class);

    public void broadcast(TestComponent component) {
        log.info("Broadcasting message: {}", component.getJson());
    }

}
