package devPilot.backend.services.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import devPilot.backend.services.ai.AiErrorsTests;

class VectorBatchWriterTests {

    /** Records batch sizes; fails the first {@code rateLimitsLeft} calls with a 429. */
    static class FakeStore implements VectorStore {
        final List<Integer> batches = new ArrayList<>();
        int rateLimitsLeft;

        @Override
        public void add(List<Document> documents) {
            if (rateLimitsLeft-- > 0) {
                throw new RuntimeException(AiErrorsTests.rateLimit("per-minute limit"));
            }
            batches.add(documents.size());
        }

        @Override
        public void delete(List<String> idList) {
        }

        @Override
        public void delete(Filter.Expression filterExpression) {
        }

        @Override
        public List<Document> similaritySearch(SearchRequest request) {
            return List.of();
        }
    }

    private static List<Document> chunks(int n) {
        return IntStream.range(0, n).mapToObj(i -> new Document("chunk " + i)).toList();
    }

    @Test
    void splitsIntoBatchesOfThirtyTwo() {
        var store = new FakeStore();
        new VectorBatchWriter(store, 0, 3, 1, 1).write(chunks(70));

        assertThat(store.batches).containsExactly(32, 32, 6);
    }

    @Test
    void retriesBatchAfterRateLimit() {
        var store = new FakeStore();
        store.rateLimitsLeft = 2;
        new VectorBatchWriter(store, 0, 3, 1, 1).write(chunks(5));

        assertThat(store.batches).containsExactly(5);
    }

    @Test
    void givesUpWithReadableMessageWhenRateLimitPersists() {
        var store = new FakeStore();
        store.rateLimitsLeft = 10;
        var writer = new VectorBatchWriter(store, 0, 2, 1, 1);

        assertThatThrownBy(() -> writer.write(chunks(5)))
                .hasMessageContaining("rate limit was reached while indexing");
        assertThat(store.batches).isEmpty();
    }

    @Test
    void dailyQuotaFailsWithoutRetrying() {
        var store = new FakeStore() {
            int calls;

            @Override
            public void add(List<Document> documents) {
                calls++;
                throw AiErrorsTests.rateLimit("Embed content requests per day limit exceeded on Free Tier.");
            }
        };

        assertThatThrownBy(() -> new VectorBatchWriter(store, 0, 3, 1, 1).write(chunks(5)))
                .hasMessageContaining("daily embedding quota");
        assertThat(store.calls).isEqualTo(1);
    }

    @Test
    void otherErrorsAreNotRetried() {
        var store = new FakeStore() {
            int calls;

            @Override
            public void add(List<Document> documents) {
                calls++;
                throw new IllegalStateException("db down");
            }
        };

        assertThatThrownBy(() -> new VectorBatchWriter(store, 0, 3, 1, 1).write(chunks(5)))
                .hasMessage("db down");
        assertThat(store.calls).isEqualTo(1);
    }
}
