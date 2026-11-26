package io.quarkiverse.langchain4j.azure.openai.test.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.LogRecord;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.quarkiverse.langchain4j.mockapi.EmbeddingReply;
import io.quarkiverse.langchain4j.mockapi.MockModelServer;
import io.quarkus.test.QuarkusExtensionTest;

/**
 * Both {@code proxy-configuration-name} and {@code proxy-host} are set: the named proxy configuration is used by every
 * model and {@code proxy-host} is ignored.
 */
public class AzureOpenAiProxyConfigurationNameTest {

    @RegisterExtension
    static final QuarkusExtensionTest test = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class).addClass(ForwardProxy.class))
            .overrideConfigKey("quarkus.langchain4j.mock-api.enabled", "true")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.api-key", "whatever")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.proxy-configuration-name", "local")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.proxy-host", "proxy.example.com")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.proxy-port", "8080")
            .overrideRuntimeConfigKey("quarkus.proxy.local.host", "localhost")
            .overrideRuntimeConfigKey("quarkus.proxy.local.port", String.valueOf(ForwardProxy.port()))
            .setLogRecordPredicate(record -> true)
            .assertLogRecords(AzureOpenAiProxyConfigurationNameTest::verifyLogRecords);

    private static void verifyLogRecords(List<LogRecord> logRecords) {
        assertThat(logRecords.stream().map(LogRecord::getMessage))
                .contains(
                        "Both 'proxy-configuration-name' (%s) and 'proxy-host' (%s) are set. The 'proxy-host' configuration will be ignored. Please remove 'proxy-host' and 'proxy-port' from your configuration.");
    }

    @Inject
    ChatModel chatModel;

    @Inject
    StreamingChatModel streamingChatModel;

    @Inject
    EmbeddingModel embeddingModel;

    @Inject
    MockModelServer mock;

    @ConfigProperty(name = "quarkus.langchain4j.mock-api.url")
    String mockUrl;

    @AfterAll
    static void stopProxy() {
        ForwardProxy.stop();
    }

    @Test
    void everyModelGoesThroughTheNamedProxy() throws Exception {
        mock.reset();
        mock.chat().reply("response");
        mock.chat().reply("streamed");
        mock.embeddings().reply(EmbeddingReply.of(new float[] { 0.1f, 0.2f }));

        assertThat(chatModel.chat("hello")).isEqualTo("response");

        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        streamingChatModel.chat("hello", new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                done.complete(response);
            }
        });
        assertThat(done.get(1, TimeUnit.MINUTES).aiMessage().text()).isEqualTo("streamed");

        assertThat(embeddingModel.embed("hello").content().vector()).containsExactly(0.1f, 0.2f);

        // the mock is always the endpoint, so only the proxy's record shows the requests went through the named proxy
        assertThat(ForwardProxy.forwardedUris()).satisfiesExactly(
                uri -> assertThat(uri).startsWith(mockUrl).contains("/chat/completions"),
                uri -> assertThat(uri).startsWith(mockUrl).contains("/chat/completions"),
                uri -> assertThat(uri).startsWith(mockUrl).contains("/embeddings"));
    }
}
