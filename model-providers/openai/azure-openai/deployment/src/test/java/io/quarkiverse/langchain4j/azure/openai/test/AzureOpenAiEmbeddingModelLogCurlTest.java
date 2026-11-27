package io.quarkiverse.langchain4j.azure.openai.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.logging.LogRecord;

import jakarta.inject.Inject;

import org.jboss.logmanager.ExtLogRecord;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.langchain4j.model.embedding.EmbeddingModel;
import io.quarkiverse.langchain4j.mockapi.EmbeddingReply;
import io.quarkiverse.langchain4j.mockapi.MockModelServer;
import io.quarkus.test.QuarkusExtensionTest;

public class AzureOpenAiEmbeddingModelLogCurlTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .setArchiveProducer(() -> ShrinkWrap.create(JavaArchive.class))
            .overrideConfigKey("quarkus.langchain4j.mock-api.enabled", "true")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.api-key", "whatever")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.log-requests-curl", "true")
            .setLogRecordPredicate(record -> record.getLoggerName().startsWith("io.quarkiverse.langchain4j.openai.common"))
            .assertLogRecords(AzureOpenAiEmbeddingModelLogCurlTest::verifyLogRecords);

    @Inject
    EmbeddingModel embeddingModel;

    @Inject
    MockModelServer mock;

    @BeforeEach
    void reset() {
        mock.reset();
        mock.embeddings().reply(EmbeddingReply.of(new float[] { 0.1f, 0.2f }));
    }

    @Test
    void embeddingRequestIsLoggedAsCurl() {
        embeddingModel.embed("hello");

        assertThat(mock.embeddings().requests()).hasSize(1);
    }

    private static void verifyLogRecords(List<LogRecord> logRecords) {
        assertThat(logRecords.stream()
                .map(record -> ((ExtLogRecord) record).getFormattedMessage())
                .filter(message -> message.startsWith("cURL:")))
                .anySatisfy(message -> assertThat(message).contains("curl").contains("/embeddings"));
    }
}
