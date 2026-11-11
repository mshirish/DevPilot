package devPilot.backend.config;

import java.io.IOException;

import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.stereotype.Component;

import okhttp3.Interceptor;
import okhttp3.Response;
import okhttp3.ResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Makes OpenAI-compatible embedding responses parseable by the OpenAI Java SDK.
 *
 * <p>Google Gemini's OpenAI-compatible endpoint omits {@code index} when it is 0 (protobuf drops
 * default values) and sends no {@code usage}; the SDK treats both as required and fails with
 * "`index` is not set". This fills them in for {@code /embeddings} responses only. OpenAI's
 * responses already contain both fields, so they pass through unchanged.
 */
@Component
public class EmbeddingResponseCompat implements OpenAiHttpClientBuilderCustomizer, Interceptor {

    private final JsonMapper jsonMapper;

    public EmbeddingResponseCompat(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void customize(SpringAiOpenAiHttpClient.Builder builder) {
        builder.interceptor(this);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Response response = chain.proceed(chain.request());
        ResponseBody body = response.body();
        if (!response.isSuccessful() || body == null
                || !chain.request().url().encodedPath().endsWith("/embeddings")) {
            return response;
        }
        String original = body.string();
        return response.newBuilder()
                .body(ResponseBody.create(normalize(original), body.contentType()))
                .build();
    }

    String normalize(String json) {
        JsonNode root;
        try {
            root = jsonMapper.readTree(json);
        } catch (RuntimeException notJson) {
            return json;
        }
        if (!(root instanceof ObjectNode response) || !response.path("data").isArray()) {
            return json;
        }
        boolean changed = false;
        int position = 0;
        for (JsonNode item : response.get("data")) {
            if (item instanceof ObjectNode embedding && !embedding.has("index")) {
                embedding.put("index", position);
                changed = true;
            }
            position++;
        }
        if (!response.has("usage")) {
            response.putObject("usage").put("prompt_tokens", 0).put("total_tokens", 0);
            changed = true;
        }
        return changed ? jsonMapper.writeValueAsString(response) : json;
    }
}
