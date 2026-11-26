package io.quarkiverse.langchain4j.openai.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.mockapi.CapturedRequest;
import io.quarkiverse.langchain4j.mockapi.ChatReply;
import io.quarkiverse.langchain4j.mockapi.MockModelServer;
import io.quarkus.test.QuarkusExtensionTest;
import tools.jackson.databind.JsonNode;

/**
 * Follows the "Implementing Semantic Compression" guide: the summary stored by its {@code CompressingChatMemoryStore}
 * is sent to the model with the next questions (#1945).
 */
public class SemanticCompressionGuideTest {

    static final String INSTRUCTIONS = "You are a polite and helpful assistant.";
    static final String SUMMARY_PREFIX = "Context: The following is a summary of the previous conversation:";

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(Assistant.class, CompressingChatMemoryStore.class))
            .overrideConfigKey("quarkus.langchain4j.mock-api.enabled", "true")
            .overrideRuntimeConfigKey("quarkus.langchain4j.openai.chat-model.mode", "chat-completion")
            .overrideRuntimeConfigKey("semantic-compression-threshold", "3");

    @RegisterAiService
    @dev.langchain4j.service.SystemMessage(INSTRUCTIONS)
    @ApplicationScoped // as in the guide
    public interface Assistant {
        String answer(@MemoryId String id, @dev.langchain4j.service.UserMessage String question);
    }

    @Inject
    Assistant assistant;

    @Inject
    CompressingChatMemoryStore store;

    @Inject
    MockModelServer mock;

    @Test
    void summaryIsSentWithTheNextQuestions() {
        mock.reset();
        mock.chat().reply(
                ChatReply.text("Hello Bob, happy to help with Athens."),
                ChatReply.text("Air France flies from Lyon to Athens."),
                ChatReply.text("Bob is planning a trip from Lyon to Athens and asked about flights."), // first summary
                ChatReply.text("A taxi takes about 40 minutes."),
                ChatReply.text("Bob is flying from Lyon to Athens and will take a taxi to the city center.")); // second summary

        assistant.answer("abc", "Hello, my name is Bob. I would like to plan a trip to Athens.");
        // 5 messages after this exchange, more than the threshold of 3: the store compresses them
        assistant.answer("abc", "What are the flights from Lyon to Athens?");

        assertThat(store.getMessages("abc")).containsExactly(
                SystemMessage.from(INSTRUCTIONS),
                SystemMessage.from(SUMMARY_PREFIX + "\nBob is planning a trip from Lyon to Athens and asked about flights."));

        assistant.answer("abc", "How long does a taxi from the airport take?");

        List<CapturedRequest> requests = mock.chat().requests();
        assertThat(requests).hasSize(5);

        String firstSummarization = requests.get(2).bodyAsJson().path("messages").get(0).path("content").asText();
        assertThat(firstSummarization)
                .startsWith("Summarize the following dialogue")
                .contains("User: What are the flights from Lyon to Athens?")
                .doesNotContain(INSTRUCTIONS);

        // the question after the compression is sent with the instructions, the summary, then the question
        JsonNode messages = requests.get(3).bodyAsJson().path("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(0).path("content").asText()).isEqualTo(INSTRUCTIONS);
        assertThat(messages.get(1).path("role").asText()).isEqualTo("system");
        assertThat(messages.get(1).path("content").asText())
                .isEqualTo(SUMMARY_PREFIX + "\nBob is planning a trip from Lyon to Athens and asked about flights.");
        assertThat(messages.get(2).path("role").asText()).isEqualTo("user");

        // the history is compressed again, starting from the previous summary
        String secondSummarization = requests.get(4).bodyAsJson().path("messages").get(0).path("content").asText();
        assertThat(secondSummarization)
                .contains("Context: Bob is planning a trip from Lyon to Athens and asked about flights.")
                .contains("User: How long does a taxi from the airport take?")
                .doesNotContain(SUMMARY_PREFIX);
        assertThat(store.getMessages("abc")).containsExactly(
                SystemMessage.from(INSTRUCTIONS),
                SystemMessage.from(SUMMARY_PREFIX
                        + "\nBob is flying from Lyon to Athens and will take a taxi to the city center."));
    }

    /**
     * Copy of docs/modules/ROOT/examples/io/quarkiverse/langchain4j/samples/compression/CompressingChatMemoryStore.java,
     * with the logging removed.
     */
    @ApplicationScoped
    public static class CompressingChatMemoryStore implements ChatMemoryStore {

        private final ChatMemoryStore delegate;
        private final ChatModel chatModel;
        private final int threshold;

        public CompressingChatMemoryStore(ChatModel model,
                @org.eclipse.microprofile.config.inject.ConfigProperty(name = "semantic-compression-threshold", defaultValue = "5") int threshold) {
            this.delegate = new InMemoryChatMemoryStore();
            this.chatModel = model;
            this.threshold = threshold;
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            if (messages.isEmpty()) {
                return;
            }
            ChatMessage lastMessage = messages.get(messages.size() - 1);
            if (lastMessage.type() == ChatMessageType.AI && ((AiMessage) lastMessage).hasToolExecutionRequests()) {
                delegate.updateMessages(memoryId, messages);
                return;
            }
            if (lastMessage.type() == ChatMessageType.SYSTEM || lastMessage.type() == ChatMessageType.TOOL_EXECUTION_RESULT) {
                delegate.updateMessages(memoryId, messages);
                return;
            }

            if (messages.size() > threshold) {
                List<ChatMessage> compressed = new ArrayList<>();
                messages.stream()
                        .filter(m -> m instanceof SystemMessage systemMessage && !isSummary(systemMessage))
                        .findFirst()
                        .ifPresent(compressed::add);

                StringBuilder sb = new StringBuilder(
                        "Summarize the following dialogue into a brief summary, preserving context and tone:\n\n");
                for (ChatMessage msg : messages) {
                    switch (msg.type()) {
                        case SYSTEM -> {
                            if (isSummary((SystemMessage) msg)) {
                                sb.append("Context: ").append(extractSummary((SystemMessage) msg)).append("\n");
                            }
                        }
                        case USER -> sb.append("User: ").append(((UserMessage) msg).singleText()).append("\n");
                        case AI -> sb.append("Assistant: ").append(((AiMessage) msg).text()).append("\n");
                        default -> {
                        }
                    }
                }
                String summary = chatModel.chat(sb.toString());

                compressed.add(SystemMessage.systemMessage(SUMMARY_PREFIX + "\n" + summary));
                delegate.updateMessages(memoryId, compressed);
            } else {
                delegate.updateMessages(memoryId, messages);
            }
        }

        private static boolean isSummary(SystemMessage systemMsg) {
            return systemMsg.text().startsWith(SUMMARY_PREFIX);
        }

        private static String extractSummary(SystemMessage summaryMsg) {
            return summaryMsg.text().substring(SUMMARY_PREFIX.length()).trim();
        }

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            return delegate.getMessages(memoryId);
        }

        @Override
        public void deleteMessages(Object memoryId) {
            delegate.deleteMessages(memoryId);
        }
    }
}
