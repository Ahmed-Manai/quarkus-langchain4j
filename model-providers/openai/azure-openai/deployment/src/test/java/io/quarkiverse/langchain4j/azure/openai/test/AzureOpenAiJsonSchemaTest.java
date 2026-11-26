package io.quarkiverse.langchain4j.azure.openai.test;

import static dev.langchain4j.model.chat.Capability.RESPONSE_FORMAT_JSON_SCHEMA;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.mockapi.ChatReply;
import io.quarkiverse.langchain4j.mockapi.MockModelServer;
import io.quarkus.test.QuarkusExtensionTest;
import tools.jackson.databind.JsonNode;

/**
 * With {@code response-format=json_schema}, the JSON schema of the requests is sent as Structured Outputs.
 */
public class AzureOpenAiJsonSchemaTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class).addClasses(PersonExtractor.class, Person.class))
            .overrideConfigKey("quarkus.langchain4j.mock-api.enabled", "true")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.chat-model.response-format", "json_schema")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.chat-model.strict-json-schema", "true");

    public record Person(String name, int age) {
    }

    @RegisterAiService
    @ApplicationScoped
    public interface PersonExtractor {
        Person extract(String text);
    }

    @Inject
    PersonExtractor extractor;

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
    void reportsTheJsonSchemaCapability() {
        assertThat(chatModel.supportedCapabilities()).contains(RESPONSE_FORMAT_JSON_SCHEMA);
        assertThat(streamingChatModel.supportedCapabilities()).contains(RESPONSE_FORMAT_JSON_SCHEMA);
    }

    @Test
    void aiServiceSendsTheSchemaOfItsReturnType() {
        mock.chat().reply("{\"name\":\"Bob\",\"age\":42}");

        assertThat(extractor.extract("Bob is 42 years old.")).isEqualTo(new Person("Bob", 42));

        JsonNode body = mock.chat().singleRequest().bodyAsJson();
        JsonNode responseFormat = body.path("response_format");
        assertThat(responseFormat.path("type").asText()).isEqualTo("json_schema");
        assertThat(responseFormat.path("json_schema").path("name").asText()).isEqualTo("Person");
        assertThat(responseFormat.path("json_schema").path("strict").asBoolean()).isTrue();
        JsonNode schema = responseFormat.path("json_schema").path("schema");
        assertThat(schema.path("type").asText()).isEqualTo("object");
        // in strict mode, the optional properties are nullable
        assertThat(schema.path("properties").path("name").path("type").toString()).contains("\"string\"");
        assertThat(schema.path("properties").path("age").path("type").toString()).contains("\"integer\"");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        // the schema replaces the format instructions in the prompt
        assertThat(body.path("messages").toString())
                .contains("Bob is 42 years old.")
                .doesNotContain("You must answer strictly in the following JSON format");
    }

    @Test
    void streamingChatModelSendsTheSchemaOfTheRequest() throws Exception {
        mock.chat().reply(ChatReply.text("{\"city\":\"Athens\"}").chunks(List.of("{\"city\":", "\"Athens\"}")));

        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("Where is the Parthenon?"))
                .responseFormat(ResponseFormat.builder()
                        .type(ResponseFormatType.JSON)
                        .jsonSchema(JsonSchema.builder()
                                .name("Place")
                                .rootElement(JsonObjectSchema.builder()
                                        .addStringProperty("city")
                                        .required("city")
                                        .build())
                                .build())
                        .build())
                .build();
        CompletableFuture<ChatResponse> response = new CompletableFuture<>();
        streamingChatModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String token) {
            }

            @Override
            public void onError(Throwable error) {
                response.completeExceptionally(error);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                response.complete(completeResponse);
            }
        });
        assertThat(response.get(1, TimeUnit.MINUTES).aiMessage().text()).isEqualTo("{\"city\":\"Athens\"}");

        JsonNode responseFormat = mock.chat().singleRequest().bodyAsJson().path("response_format");
        assertThat(responseFormat.path("type").asText()).isEqualTo("json_schema");
        assertThat(responseFormat.path("json_schema").path("name").asText()).isEqualTo("Place");
        assertThat(responseFormat.path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(responseFormat.path("json_schema").path("schema").path("properties").path("city").path("type").asText())
                .isEqualTo("string");
    }
}
