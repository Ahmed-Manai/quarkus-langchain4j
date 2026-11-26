package io.quarkiverse.langchain4j.azure.openai.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import dev.langchain4j.model.chat.ChatModel;
import io.quarkiverse.langchain4j.ModelName;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.smallrye.certs.Format;
import io.smallrye.certs.junit5.Certificate;
import io.smallrye.certs.junit5.Certificates;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;

/**
 * Each Azure OpenAI configuration trusts the server through its own named TLS configuration. A server certificate that
 * is only in the truststore of the other configuration would make the request fail.
 */
@Certificates(baseDir = "target/certs", certificates = {
        @Certificate(name = "lc4j", password = "secret", formats = Format.PKCS12),
        @Certificate(name = "lc4jA", password = "secretA", formats = Format.PKCS12)
})
public class AzureOpenAiTlsConfigurationTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot((jar) -> jar
                    .addClasses(Setup.class)
                    .addAsResource(new File("target/certs/lc4j-keystore.p12"), "keystore.p12")
                    .addAsResource(new File("target/certs/lc4j-truststore.p12"), "truststore.p12")
                    .addAsResource(new File("target/certs/lc4jA-keystore.p12"), "keystoreA.p12")
                    .addAsResource(new File("target/certs/lc4jA-truststore.p12"), "truststoreA.p12"))

            .overrideConfigKey("quarkus.tls.server.key-store.jks.path", "keystore.p12")
            .overrideConfigKey("quarkus.tls.server.key-store.jks.password", "secret")

            .overrideConfigKey("quarkus.tls.serverA.key-store.jks.path", "keystoreA.p12")
            .overrideConfigKey("quarkus.tls.serverA.key-store.jks.password", "secretA")

            .overrideConfigKey("quarkus.tls.client.trust-store.jks.path", "truststore.p12")
            .overrideConfigKey("quarkus.tls.client.trust-store.jks.password", "secret")

            .overrideConfigKey("quarkus.tls.clientA.trust-store.jks.path", "truststoreA.p12")
            .overrideConfigKey("quarkus.tls.clientA.trust-store.jks.password", "secretA")

            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.api-key", "key")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.tls-configuration-name", "client")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.endpoint", "https://localhost:8446/s")

            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.clientA.api-key", "keyA")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.clientA.tls-configuration-name", "clientA")
            .overrideRuntimeConfigKey("quarkus.langchain4j.azure-openai.clientA.endpoint", "https://localhost:8447/sA");

    @Inject
    ChatModel defaultChatModel;

    @ModelName("clientA")
    ChatModel clientAChatModel;

    @Test
    void test() {
        assertThat(defaultChatModel.chat("hello")).isEqualTo("Hello");

        assertThat(clientAChatModel.chat("hello")).isEqualTo("HelloA");
    }

    @ApplicationScoped
    public static class Setup {

        private static final String RESPONSE = """
                {
                  "id": "chatcmpl-123",
                  "object": "chat.completion",
                  "created": 1677652288,
                  "model": "gpt-4o-mini",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "REPLACE_ME"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 9,
                    "completion_tokens": 12,
                    "total_tokens": 21
                  }
                }
                """;

        private final List<HttpServer> servers = Collections.synchronizedList(new ArrayList<>());

        public void start(@Observes StartupEvent ev, Vertx vertx, TlsConfigurationRegistry tlsConfigurationRegistry) {
            // server for the default configuration
            servers.add(createServer(vertx, tlsConfigurationRegistry, "server", 8446, "/s", "Hello"));
            // server for clientA
            servers.add(createServer(vertx, tlsConfigurationRegistry, "serverA", 8447, "/sA", "HelloA"));
        }

        private HttpServer createServer(Vertx vertx, TlsConfigurationRegistry tlsConfigurationRegistry,
                String tlsConfigurationName, int port, String basePath, String chatResponse) {
            TlsConfiguration tlsConfiguration = tlsConfigurationRegistry.get(tlsConfigurationName).orElseThrow();
            HttpServerOptions serverOptions = new HttpServerOptions()
                    .setSsl(true)
                    .setPort(port)
                    .setKeyCertOptions(tlsConfiguration.getKeyStoreOptions());
            return vertx.createHttpServer(serverOptions)
                    .requestHandler(request -> {
                        if (request.path().equals(basePath + "/chat/completions")) {
                            request.response()
                                    .putHeader("Content-Type", "application/json")
                                    .end(RESPONSE.replace("REPLACE_ME", chatResponse));
                        } else {
                            request.response().setStatusCode(404).end();
                        }
                    })
                    .listen().toCompletionStage().toCompletableFuture().join();
        }

        public void shutdown(@Observes ShutdownEvent ev) {
            for (HttpServer server : servers) {
                server.close();
            }
        }
    }
}
