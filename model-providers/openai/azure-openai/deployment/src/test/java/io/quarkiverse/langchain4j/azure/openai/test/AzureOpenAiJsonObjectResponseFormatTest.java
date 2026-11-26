package io.quarkiverse.langchain4j.azure.openai.test;

import static dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;

import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.quarkiverse.langchain4j.mockapi.MockModelServer;
import io.quarkus.test.QuarkusExtensionTest;
import tools.jackson.databind.JsonNode;

/**
 * {@code response-format=json_object} keeps sending JSON mode with every request, without reporting JSON schema
 * support.
 */
public class AzureOpenAiJsonObjectResponseFormatTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class))
            .overrideConfigKey("quarkus.langchain4j.mock-api.enabled", "true")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.chat-model.response-format", "JSON_OBJECT");

    @Inject
    ChatModel chatModel;

    @Inject
    StreamingChatModel streamingChatModel;

    @Inject
    MockModelServer mock;

    @BeforeEach
    void reset() {
        mock.reset();
    }

    @Test
    void doesNotReportTheJsonSchemaCapability() {
        assertThat(chatModel.supportedCapabilities()).doesNotContain(RESPONSE_FORMAT_JSON_SCHEMA);
        assertThat(streamingChatModel.supportedCapabilities()).doesNotContain(RESPONSE_FORMAT_JSON_SCHEMA);
    }

    @Test
    void sendsJsonMode() {
        mock.chat().reply("{\"answer\":\"Athens\"}");

        assertThat(chatModel.chat("What is the capital of Greece? Answer in JSON.")).isEqualTo("{\"answer\":\"Athens\"}");

        JsonNode responseFormat = mock.chat().singleRequest().bodyAsJson().path("response_format");
        assertThat(responseFormat.path("type").asText()).isEqualTo("json_object");
        assertThat(responseFormat.has("json_schema")).isFalse();
    }
}
