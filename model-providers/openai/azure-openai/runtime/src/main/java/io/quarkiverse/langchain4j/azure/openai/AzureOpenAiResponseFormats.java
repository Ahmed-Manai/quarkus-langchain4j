package io.quarkiverse.langchain4j.azure.openai;

import static dev.langchain4j.internal.JsonSchemaElementUtils.toMap;
import static dev.langchain4j.model.chat.request.ResponseFormatType.TEXT;

import java.util.Locale;

import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.openai.internal.chat.ResponseFormatType;

/**
 * Maps the response formats of the Azure OpenAI chat models to the Chat Completions API, the same way as
 * LangChain4j's {@code OpenAiChatModel}.
 */
final class AzureOpenAiResponseFormats {

    private AzureOpenAiResponseFormats() {
    }

    /**
     * Validates the configured {@code response-format} ({@code text}, {@code json_object} or {@code json_schema},
     * in any case) and returns it in lower case.
     */
    static String validate(String responseFormat) {
        if (responseFormat == null) {
            return null;
        }
        return ResponseFormatType.valueOf(responseFormat.toUpperCase(Locale.ROOT)).name().toLowerCase(Locale.ROOT);
    }

    /**
     * The response format of the requests that don't set one: JSON mode when {@code response-format} is
     * {@code json_object}. With {@code json_schema}, the schema comes from each request.
     */
    static ResponseFormat defaultResponseFormat(String responseFormat) {
        return "json_object".equals(responseFormat) ? ResponseFormat.JSON : null;
    }

    /**
     * Whether the model should report {@code RESPONSE_FORMAT_JSON_SCHEMA}, so that AI services returning a POJO send
     * its JSON schema.
     */
    static boolean supportsJsonSchema(String responseFormat) {
        return "json_schema".equals(responseFormat);
    }

    static dev.langchain4j.model.openai.internal.chat.ResponseFormat toOpenAiResponseFormat(ResponseFormat responseFormat,
            boolean strict) {
        if (responseFormat == null || responseFormat.type() == TEXT) {
            return null;
        }

        JsonSchema jsonSchema = responseFormat.jsonSchema();
        if (jsonSchema == null) {
            return dev.langchain4j.model.openai.internal.chat.ResponseFormat.builder()
                    .type(ResponseFormatType.JSON_OBJECT)
                    .build();
        }
        if (!(jsonSchema.rootElement() instanceof JsonObjectSchema || jsonSchema.rootElement() instanceof JsonRawSchema)) {
            throw new IllegalArgumentException(
                    "For Azure OpenAI, the root element of the JSON Schema must be either a JsonObjectSchema or a JsonRawSchema, but it was: "
                            + jsonSchema.rootElement().getClass());
        }
        return dev.langchain4j.model.openai.internal.chat.ResponseFormat.builder()
                .type(ResponseFormatType.JSON_SCHEMA)
                .jsonSchema(dev.langchain4j.model.openai.internal.chat.JsonSchema.builder()
                        .name(jsonSchema.name())
                        .strict(strict)
                        .schema(toMap(jsonSchema.rootElement(), strict))
                        .build())
                .build();
    }
}
