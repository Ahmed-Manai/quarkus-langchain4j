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
 * Reproducer for #1945, following the "Implementing Semantic Compression" guide: the summary that the guide's
 * {@code CompressingChatMemoryStore} appends to the system message must still be sent to the model on the next request.
 */
public class SemanticCompressionGuideTest {

    static final String SUMMARY_PREFIX = "Context: The following is a summary of the previous conversation:";

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class)
                    .addClasses(Assistant.class, CompressingChatMemoryStore.class))
            .overrideConfigKey("quarkus.langchain4j.mock-api.enabled", "true")
            .overrideRuntimeConfigKey("quarkus.langchain4j.openai.chat-model.mode", "chat-completion")
            .overrideRuntimeConfigKey("semantic-compression-threshold", "3");

    @RegisterAiService
    @dev.langchain4j.service.SystemMessage("You are a polite and helpful assistant.")
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
    void summaryIsSentOnTheNextRequest() {
        mock.reset();
        mock.chat().reply(
                ChatReply.text("Hello Bob, happy to help with Athens."),
                ChatReply.text("Air France flies from Lyon to Athens."),
                ChatReply.text("Bob is planning a trip from Lyon to Athens and asked about flights."), // the summary
                ChatReply.text("A taxi takes about 40 minutes."));

        assistant.answer("abc", "Hello, my name is Bob. I would like to plan a trip to Athens.");
        // 5 messages after this exchange, more than the threshold of 3: the store compresses them into the system message
        assistant.answer("abc", "What are the flights from Lyon to Athens?");

        List<ChatMessage> stored = store.getMessages("abc");
        assertThat(stored).singleElement().isInstanceOf(SystemMessage.class);
        assertThat(((SystemMessage) stored.get(0)).text()).contains(SUMMARY_PREFIX);

        assistant.answer("abc", "How long does a taxi from the airport take?");

        List<CapturedRequest> requests = mock.chat().requests();
        assertThat(requests).hasSize(4);
        assertThat(requests.get(2).body()).contains("Summarize the following dialogue");

        JsonNode systemMessage = requests.get(3).bodyAsJson().path("messages").get(0);
        assertThat(systemMessage.path("role").asText()).isEqualTo("system");
        // the guide shows the summary in the system message of this request
        assertThat(systemMessage.path("content").asText())
                .as("system message sent with the third question")
                .contains(SUMMARY_PREFIX);
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
                SystemMessage systemMsg = (SystemMessage) messages.stream()
                        .filter(m -> m.type() == ChatMessageType.SYSTEM)
                        .findFirst().orElse(null);
                for (ChatMessage msg : messages) {
                    if (msg.type() == ChatMessageType.SYSTEM) {
                        extractSummaryFromSystemMessageIfAny((SystemMessage) msg, compressed);
                    } else {
                        compressed.add(msg);
                    }
                }
                StringBuilder sb = new StringBuilder(
                        "Summarize the following dialogue into a brief summary, preserving context and tone:\n\n");
                for (ChatMessage msg : messages) {
                    switch (msg.type()) {
                        case SYSTEM -> sb.append("Context: ").append(((SystemMessage) msg).text()).append("\n");
                        case USER -> sb.append("User: ").append(((UserMessage) msg).singleText()).append("\n");
                        case AI -> sb.append("Assistant: ").append(((AiMessage) msg).text()).append("\n");
                        default -> {
                        }
                    }
                }
                String summary = chatModel.chat(sb.toString());
                systemMsg = appendSummaryToSystemMessage(systemMsg, summary);
                delegate.updateMessages(memoryId, List.of(systemMsg));
            } else {
                delegate.updateMessages(memoryId, messages);
            }
        }

        private SystemMessage appendSummaryToSystemMessage(SystemMessage systemMsg, String summary) {
            if (systemMsg == null) {
                return SystemMessage.systemMessage(SUMMARY_PREFIX + "\n" + summary);
            }
            String content = systemMsg.text();
            if (content.contains(SUMMARY_PREFIX)) {
                int startIndex = content.indexOf(SUMMARY_PREFIX) + SUMMARY_PREFIX.length();
                String newContent = content.substring(0, startIndex) + "\n\n";
                newContent = newContent + "\n\n" + SUMMARY_PREFIX + "\n" + summary;
                return SystemMessage.systemMessage(newContent);
            } else {
                return SystemMessage.systemMessage(content + "\n\n" + SUMMARY_PREFIX + "\n" + summary);
            }
        }

        private void extractSummaryFromSystemMessageIfAny(SystemMessage systemMsg, List<ChatMessage> compressed) {
            String content = systemMsg.text();
            if (content.contains(SUMMARY_PREFIX)) {
                int startIndex = content.indexOf(SUMMARY_PREFIX) + SUMMARY_PREFIX.length();
                compressed.add(SystemMessage.systemMessage(content.substring(startIndex).trim()));
            }
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
