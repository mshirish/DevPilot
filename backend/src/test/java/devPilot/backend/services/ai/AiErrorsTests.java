package devPilot.backend.services.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.openai.core.http.Headers;
import com.openai.errors.RateLimitException;
import com.openai.models.ErrorObject;

public class AiErrorsTests {

    public static RateLimitException rateLimit(String message) {
        return RateLimitException.builder()
                .headers(Headers.builder().build())
                .error(ErrorObject.builder().code("429").message(message).param((String) null).type("RESOURCE_EXHAUSTED").build())
                .build();
    }

    @Test
    void detectsRateLimitWrappedInOtherExceptions() {
        var wrapped = new RuntimeException("stream failed", new CompletionException(rateLimit("slow down")));

        assertThat(AiErrors.isRateLimited(wrapped)).isTrue();
        assertThat(AiErrors.userMessage(wrapped)).isEqualTo(AiErrors.RATE_LIMITED_MESSAGE);
    }

    @Test
    void ignoresOtherErrors() {
        assertThat(AiErrors.isRateLimited(new IllegalStateException("boom"))).isFalse();
        assertThat(AiErrors.isRateLimited(null)).isFalse();
    }

    @Test
    void recognisesExhaustedDailyQuota() {
        var daily = rateLimit("Quota exceeded, quotaId: GenerateRequestsPerDayPerProjectPerModel-FreeTier");

        assertThat(AiErrors.userMessage(daily)).isEqualTo(AiErrors.DAILY_QUOTA_MESSAGE);
    }

    @Test
    void recognisesDailyQuotaFromGeminiMessageText() {
        // Message text Gemini actually returned when the free embedding quota ran out
        var daily = rateLimit("Quota exceeded for metric: generativelanguage.googleapis.com/embed_content_free_tier_requests, "
                + "limit: 1000, model: gemini-embedding-1.0, description: Embed content requests per day limit exceeded on Free Tier.");
        var perMinute = rateLimit("description: Embed content requests per minute limit exceeded on Free Tier.");

        assertThat(AiErrors.isDailyQuota(daily)).isTrue();
        assertThat(AiErrors.isDailyQuota(perMinute)).isFalse();
    }
}
