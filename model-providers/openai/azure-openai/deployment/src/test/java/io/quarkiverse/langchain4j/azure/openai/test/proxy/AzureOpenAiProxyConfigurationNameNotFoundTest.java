package io.quarkiverse.langchain4j.azure.openai.test.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.mockapi.MockModelServer;
import io.quarkus.test.QuarkusExtensionTest;

public class AzureOpenAiProxyConfigurationNameNotFoundTest {

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class))
            .overrideConfigKey("quarkus.langchain4j.mock-api.enabled", "true")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.api-key", "whatever")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.proxy-configuration-name", "my-proxy");

    @Inject
    ChatModel chatModel;

    @Inject
    MockModelServer mock;

    @Test
    void unknownProxyConfigurationNameFails() {
        mock.reset();
        assertThatThrownBy(() -> chatModel.chat("hello"))
                .hasMessageContaining(
                        "Proxy configuration with name my-proxy was requested but quarkus.proxy.\"my-proxy\".host is not defined");
        assertThat(mock.requests()).isEmpty();
    }
}
