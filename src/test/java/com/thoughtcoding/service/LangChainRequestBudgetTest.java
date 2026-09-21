package com.thoughtcoding.service;

import com.thoughtcoding.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LangChainRequestBudgetTest {

    @Test
    void fixedBudgetIncludesConfiguredMaximumOutputTokens() {
        AppConfig config = new AppConfig();
        AppConfig.ModelConfig model = new AppConfig.ModelConfig();
        model.setName("test-model");
        model.setBaseURL("http://localhost");
        model.setApiKey("test-key");
        model.setMaxTokens(1_234);
        config.setModels(Map.of("test", model));
        config.setDefaultModel("test");
        LangChainService service = new LangChainService(config, null, null);

        int fixed = service.estimateFixedRequestTokens("system prompt", "recall text", null);

        assertTrue(fixed >= 1_234);
    }
}
