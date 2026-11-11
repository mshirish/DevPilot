package devPilot.backend.services.indexing;

import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import devPilot.backend.services.ai.AiErrors;
import lombok.extern.slf4j.Slf4j;

/**
 * Embeds and stores chunks in fixed-size batches, paced for free-tier embedding APIs.
 *
 * <p>Each batch is one embeddings request of up to {@value #BATCH_SIZE} chunks (~200 tokens each).
 * Batches are spaced so at most {@code app.indexing.embedding-chunks-per-minute} chunks are sent
 * per minute (Gemini's free tier counts every input as one request, 100 per minute). On a 429 it
 * backs off much longer than the SDK's own retries (which cap at 8s, too short for a per-minute
 * window) and retries the batch. Retrying is safe: nothing is written until the batch is embedded.
 * Synchronized so concurrent indexing jobs share one budget.
 */
@Component
@Slf4j
public class VectorBatchWriter {

    static final int BATCH_SIZE = 32;

    private final VectorStore vectorStore;
    private final long batchDelayMs;
    private final int maxRateLimitRetries;
    private final long initialBackoffMs;
    private final long maxBackoffMs;

    private long lastWriteAt;

    public VectorBatchWriter(
            VectorStore vectorStore,
            @Value("${app.indexing.embedding-chunks-per-minute:0}") int chunksPerMinute,
            @Value("${app.indexing.rate-limit-max-retries:6}") int maxRateLimitRetries,
            @Value("${app.indexing.rate-limit-initial-backoff-ms:30000}") long initialBackoffMs,
            @Value("${app.indexing.rate-limit-max-backoff-ms:120000}") long maxBackoffMs) {
        this.vectorStore = vectorStore;
        // 0 = no throttling (e.g. paid OpenAI tiers)
        this.batchDelayMs = chunksPerMinute > 0 ? (long) Math.ceil(60_000.0 * BATCH_SIZE / chunksPerMinute) : 0;
        this.maxRateLimitRetries = maxRateLimitRetries;
        this.initialBackoffMs = initialBackoffMs;
        this.maxBackoffMs = maxBackoffMs;
    }

    /** Writes all chunks, splitting them into batches. Throws if the provider keeps rate-limiting. */
    public synchronized void write(List<Document> chunks) {
        for (int from = 0; from < chunks.size(); from += BATCH_SIZE) {
            writeBatch(chunks.subList(from, Math.min(from + BATCH_SIZE, chunks.size())));
        }
    }

    private void writeBatch(List<Document> batch) {
        long backoff = initialBackoffMs;
        for (int attempt = 0; ; attempt++) {
            throttle();
            try {
                vectorStore.add(batch);
                return;
            } catch (RuntimeException ex) {
                if (!AiErrors.isRateLimited(ex)) {
                    throw ex;
                }
                if (AiErrors.isDailyQuota(ex)) {
                    // Waiting minutes won't help; free quotas reset at midnight Pacific time
                    throw new IllegalStateException(
                            "The AI provider's daily embedding quota is used up. Please try indexing again tomorrow.",
                            ex);
                }
                if (attempt >= maxRateLimitRetries) {
                    throw new IllegalStateException(
                            "The AI provider's rate limit was reached while indexing. Please try again later.",
                            ex);
                }
                log.warn("Embedding rate-limited, retry {}/{} in {} ms",
                        attempt + 1, maxRateLimitRetries, backoff);
                sleep(backoff);
                backoff = Math.min(backoff * 2, maxBackoffMs);
            }
        }
    }

    private void throttle() {
        long wait = lastWriteAt + batchDelayMs - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
        lastWriteAt = System.currentTimeMillis();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Indexing interrupted", ex);
        }
    }
}
