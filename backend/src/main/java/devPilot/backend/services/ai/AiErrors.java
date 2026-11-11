package devPilot.backend.services.ai;

import java.util.Locale;

import com.openai.errors.OpenAIServiceException;

/**
 * Recognises errors from the OpenAI-compatible provider (OpenAI, Gemini, ...) so callers can
 * react to rate limits instead of failing with a generic error.
 */
public final class AiErrors {

    public static final String RATE_LIMITED_MESSAGE =
            "The AI service is getting too many requests right now. Please wait a minute and try again.";
    public static final String DAILY_QUOTA_MESSAGE =
            "The AI service has used up today's free quota. Please try again tomorrow.";

    private AiErrors() {
    }

    /** True when the provider answered 429 (rate limit or quota exhausted), anywhere in the cause chain. */
    public static boolean isRateLimited(Throwable error) {
        return findRateLimit(error) != null;
    }

    /**
     * True when the 429 is a daily quota (retrying within minutes won't help). Gemini says
     * "... requests per day limit exceeded" and names the quota, e.g.
     * "EmbedContentRequestsPerDayPerUserPerProjectPerModel-FreeTier".
     */
    public static boolean isDailyQuota(Throwable error) {
        OpenAIServiceException limit = findRateLimit(error);
        if (limit == null) {
            return false;
        }
        String detail = (limit.getMessage() + " " + limit.body()).toLowerCase(Locale.ROOT);
        return detail.contains("perday") || detail.contains("per day");
    }

    /** Message to show the user for a rate-limited request. */
    public static String userMessage(Throwable error) {
        return isDailyQuota(error) ? DAILY_QUOTA_MESSAGE : RATE_LIMITED_MESSAGE;
    }

    private static OpenAIServiceException findRateLimit(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof OpenAIServiceException service && service.statusCode() == 429) {
                return service;
            }
        }
        return null;
    }
}
