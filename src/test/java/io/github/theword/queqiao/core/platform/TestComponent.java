package io.github.theword.queqiao.core.platform;

import com.google.gson.JsonElement;

public class TestComponent {

    private final String json;

    public TestComponent(JsonElement jsonElement) {
        this.json = jsonElement.toString();
    }

    public String getJson() {
        return json;
    }
    
}
