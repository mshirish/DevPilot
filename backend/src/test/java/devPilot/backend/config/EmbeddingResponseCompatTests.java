package devPilot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EmbeddingResponseCompatTests {

    private final JsonMapper mapper = JsonMapper.builder().build();
    private final EmbeddingResponseCompat compat = new EmbeddingResponseCompat(mapper);

    @Test
    void fillsInIndexAndUsageThatGeminiOmits() {
        // Real Gemini shape: index 0 is dropped, no usage block
        String gemini = """
                {"object":"list","model":"gemini-embedding-001","data":[
                  {"object":"embedding","embedding":[0.1,0.2]},
                  {"object":"embedding","index":1,"embedding":[0.3,0.4]}]}""";

        JsonNode fixed = mapper.readTree(compat.normalize(gemini));

        assertThat(fixed.get("data").get(0).get("index").asInt()).isZero();
        assertThat(fixed.get("data").get(1).get("index").asInt()).isEqualTo(1);
        assertThat(fixed.get("usage").get("prompt_tokens").asInt()).isZero();
        assertThat(fixed.get("usage").get("total_tokens").asInt()).isZero();
    }

    @Test
    void leavesCompleteOpenAiResponsesUntouched() {
        String openAi = """
                {"object":"list","model":"text-embedding-3-small","data":[
                  {"object":"embedding","index":0,"embedding":[0.1]}],
                 "usage":{"prompt_tokens":3,"total_tokens":3}}""";

        assertThat(compat.normalize(openAi)).isSameAs(openAi);
    }

    @Test
    void leavesNonJsonAlone() {
        assertThat(compat.normalize("not json")).isEqualTo("not json");
    }
}
