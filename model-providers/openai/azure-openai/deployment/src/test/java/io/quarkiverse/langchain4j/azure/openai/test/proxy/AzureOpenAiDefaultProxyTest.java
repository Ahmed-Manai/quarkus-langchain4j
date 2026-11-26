package io.quarkiverse.langchain4j.azure.openai.test.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.mockapi.MockModelServer;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * Without {@code proxy-configuration-name} and {@code proxy-host}, the default proxy configuration
 * ({@code quarkus.proxy.*}) is used.
 */
public class AzureOpenAiDefaultProxyTest {

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class).addClass(ForwardProxy.class))
            .overrideConfigKey("quarkus.langchain4j.mock-api.enabled", "true")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.api-key", "whatever")
            .overrideRuntimeConfigKey("quarkus.proxy.host", "localhost")
            .overrideRuntimeConfigKey("quarkus.proxy.port", String.valueOf(ForwardProxy.port()));

    @Inject
    ChatModel chatModel;

    @Inject
    MockModelServer mock;

    @ConfigProperty(name = "quarkus.langchain4j.mock-api.url")
    String mockUrl;

    @AfterAll
    static void stopProxy() {
        ForwardProxy.stop();
    }

    @Test
    void defaultProxyIsUsed() {
        mock.reset();
        mock.chat().reply("response");

        assertThat(chatModel.chat("hello")).isEqualTo("response");

        assertThat(ForwardProxy.forwardedUris()).singleElement()
                .satisfies(uri -> assertThat(uri).startsWith(mockUrl).contains("/chat/completions"));
    }
}
