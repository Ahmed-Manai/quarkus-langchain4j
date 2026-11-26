package io.quarkiverse.langchain4j.runtime.aiservice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

class DefaultCommittableChatMemoryTest {

    @Test
    void shouldKeepTheOtherSystemMessagesWhenTheFirstOneIsUnchanged() {
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        store.updateMessages("test-memory", List.of(
                SystemMessage.from("system"),
                SystemMessage.from("summary"),
                UserMessage.from("hello")));
        DefaultCommittableChatMemory memory = new DefaultCommittableChatMemory(createDelegate(store));

        memory.add(SystemMessage.from("system"));
        memory.add(UserMessage.from("again"));
        memory.commit();

        List<ChatMessage> expected = List.of(
                SystemMessage.from("system"),
                SystemMessage.from("summary"),
                UserMessage.from("hello"),
                UserMessage.from("again"));
        assertEquals(expected, memory.messages());
        assertEquals(expected, store.getMessages("test-memory"));
    }

    @Test
    void shouldReplaceTheFirstSystemMessageWhenItChanges() {
        ChatMemoryStore store = new InMemoryChatMemoryStore();
        store.updateMessages("test-memory", List.of(
                SystemMessage.from("system-1"),
                SystemMessage.from("summary"),
                UserMessage.from("hello")));
        DefaultCommittableChatMemory memory = new DefaultCommittableChatMemory(createDelegate(store));

        memory.add(SystemMessage.from("system-2"));
        memory.commit();

        List<ChatMessage> expected = List.of(
                SystemMessage.from("system-2"),
                SystemMessage.from("summary"),
                UserMessage.from("hello"));
        assertEquals(expected, memory.messages());
        assertEquals(expected, store.getMessages("test-memory"));
    }

    private static MessageWindowChatMemory createDelegate(ChatMemoryStore store) {
        return MessageWindowChatMemory.builder()
                .id("test-memory")
                .maxMessages(10)
                .chatMemoryStore(store)
                .build();
    }
}
