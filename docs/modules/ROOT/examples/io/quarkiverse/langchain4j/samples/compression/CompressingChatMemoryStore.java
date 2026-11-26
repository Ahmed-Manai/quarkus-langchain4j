package io.quarkiverse.langchain4j.samples.compression;

import java.util.ArrayList;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import io.quarkus.logging.Log;

@ApplicationScoped
public class CompressingChatMemoryStore implements ChatMemoryStore {

    /**
     * The delegate store that will hold the chat messages.
     * This could be a database-backed store, but for simplicity,
     * we are using an in-memory store here.
     */
    private final ChatMemoryStore delegate;

    /**
     * The chat model used for summarization.
     */
    private final ChatModel chatModel;

    /**
     * The threshold for the number of messages before compression is triggered.
     */
    private final int threshold;

    /**
     * The prefix of the system message holding the summary.
     * This is used to tell the summary apart from the system message of the AI service.
     */
    public static final String SUMMARY_PREFIX = "Context: The following is a summary of the previous conversation:";

    public CompressingChatMemoryStore(ChatModel model, // We use the default chat model, but you can select any chat model
            @ConfigProperty(name = "semantic-compression-threshold", defaultValue = "5") int threshold) {
        this.delegate = new InMemoryChatMemoryStore();
        this.chatModel = model;
        this.threshold = threshold;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        // Extract the last message if any, as we do not want to compress during function calls
        if (messages.isEmpty()) {
            Log.warnf("No messages to compress for memory ID: %s", memoryId);
            return;
        }
        ChatMessage lastMessage = messages.get(messages.size() - 1);
        if (lastMessage.type() == ChatMessageType.AI && ((AiMessage) lastMessage).hasToolExecutionRequests()) {
            Log.infof("Skipping compression for memory ID: %s due to function call in the last message", memoryId);
            delegate.updateMessages(memoryId, messages);
            return;
        }
        // Also skip compression if the last message is a system message or a function call response
        if (lastMessage.type() == ChatMessageType.SYSTEM || lastMessage.type() == ChatMessageType.TOOL_EXECUTION_RESULT) {
            Log.infof(
                    "Skipping compression for memory ID: %s due to system message or function call response in the last message",
                    memoryId);
            delegate.updateMessages(memoryId, messages);
            return;
        }

        // If the number of messages exceeds the threshold, compress them
        if (messages.size() > threshold) {
            Log.infof("Triggering semantic compression for memory ID: %s with %d messages", memoryId, messages.size());
            List<ChatMessage> compressed = new ArrayList<>();

            // Keep the system message of the AI service unchanged, in the first position.
            // The AI service adds its system message on each call, and replaces the first system message of the memory
            // if it is different, so the summary must not be part of it.
            messages.stream()
                    .filter(m -> m instanceof SystemMessage systemMessage && !isSummary(systemMessage))
                    .findFirst()
                    .ifPresent(compressed::add);

            // Build compression prompt, including the previous summary if any
            StringBuilder sb = new StringBuilder(
                    "Summarize the following dialogue into a brief summary, preserving context and tone:\n\n");
            for (ChatMessage msg : messages) {
                switch (msg.type()) {
                    case SYSTEM -> {
                        if (isSummary((SystemMessage) msg)) {
                            sb.append("Context: ").append(extractSummary((SystemMessage) msg)).append("\n");
                        }
                        // The system message of the AI service is not part of the dialogue
                    }
                    case USER -> sb.append("User: ").append(((UserMessage) msg).singleText()).append("\n");
                    case AI -> sb.append("Assistant: ").append(((AiMessage) msg).text()).append("\n");
                    default -> {
                        // Ignore other message types for compression
                    }
                }
            }
            String summary = chatModel.chat(sb.toString());

            // Store the summary in its own system message, after the system message of the AI service
            SystemMessage summaryMessage = SystemMessage.systemMessage(SUMMARY_PREFIX + "\n" + summary);
            Log.infof("Generated summary: %s", summaryMessage.text());
            compressed.add(summaryMessage);
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
