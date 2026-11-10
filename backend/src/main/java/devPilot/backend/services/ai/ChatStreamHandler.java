package devPilot.backend.services.ai;

import java.util.List;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import devPilot.backend.dto.ChatMessageResponse;
import devPilot.backend.dto.CitationDto;
import devPilot.backend.entity.ChatMessage;
import devPilot.backend.entity.MessageRole;
import devPilot.backend.repository.ChatMessageRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Generation step: call the LLM (OpenAI-compatible API) via Spring AI and stream tokens to the browser over SSE.
 */
@Component
@Slf4j
public class ChatStreamHandler {

    private static final String STREAM_ERROR_MESSAGE = "The AI response failed. Please try again.";

    private final ChatClient chatClient;
    private final ChatMessageRepository chatMessageRepository;
    private final CitationMapper citationMapper;
    private final JsonMapper jsonMapper;

    public ChatStreamHandler(
            ChatClient.Builder chatClientBuilder,
            ChatMessageRepository chatMessageRepository,
            CitationMapper citationMapper,
            JsonMapper jsonMapper) {
        // ChatClient is thread-safe, so build it once and reuse it for every message
        this.chatClient = chatClientBuilder.build();
        this.chatMessageRepository = chatMessageRepository;
        this.citationMapper = citationMapper;
        this.jsonMapper = jsonMapper;
    }

    public SseEmitter stream(
            UUID sessionId,
            ChatMessageResponse savedUserMessage,
            List<CitationDto> citations,
            String systemPrompt,
            String userPrompt) {

        SseEmitter emitter = new SseEmitter(RagSettings.STREAM_TIMEOUT_MS);
        StringBuilder fullReply = new StringBuilder();

        try {
            emitter.send(SseEmitter.event()
                    .name("user_message")
                    .data(savedUserMessage));

            Disposable subscription = chatClient
                    .prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .stream()
                    .content()
                    .subscribe(
                            token -> appendToken(emitter, fullReply, token),
                            err -> failStream(emitter, err, savedUserMessage.id()),
                            () -> completeStream(emitter, sessionId, fullReply, citations));

            // Stop generating (and paying for) tokens if the client disconnects or the stream times out
            emitter.onTimeout(subscription::dispose);
            emitter.onError(err -> subscription.dispose());
        } catch (Exception ex) {
            // The request never started (or the client already left): no answer will be saved
            discardUserMessage(savedUserMessage.id());
            emitter.completeWithError(ex);
        }

        return emitter;
    }

    private void appendToken(SseEmitter emitter, StringBuilder fullReply, String token) {
        fullReply.append(token);
        try {
            emitter.send(SseEmitter.event()
                    .name("token")
                    .data(toJsonString(token)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void failStream(SseEmitter emitter, Throwable err, UUID userMessageId) {
        // No answer will be saved: drop the question so it doesn't sit unanswered in the thread
        // or count toward the visitor's question cap. The client removes it and offers a retry.
        discardUserMessage(userMessageId);
        String message;
        if (AiErrors.isRateLimited(err)) {
            log.warn("Chat stream rate-limited by AI provider: {}", err.getMessage());
            message = AiErrors.userMessage(err);
        } else {
            log.error("Chat stream error", err);
            message = STREAM_ERROR_MESSAGE;
        }
        try {
            // Tell the browser why the stream ended instead of closing it silently
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(toJsonString(message)));
            emitter.complete();
        } catch (Exception sendFailed) {
            // Client already disconnected (e.g. user pressed Stop)
            emitter.completeWithError(sendFailed);
        }
    }

    private void discardUserMessage(UUID userMessageId) {
        try {
            chatMessageRepository.deleteById(userMessageId);
        } catch (Exception ex) {
            log.warn("Could not remove unanswered message {}: {}", userMessageId, ex.getMessage());
        }
    }

    private void completeStream(
            SseEmitter emitter,
            UUID sessionId,
            StringBuilder fullReply,
            List<CitationDto> citations) {
        try {
            ChatMessage assistant = chatMessageRepository.save(ChatMessage.builder()
                    .sessionId(sessionId)
                    .role(MessageRole.ASSISTANT)
                    .content(fullReply.toString())
                    .citations(citationMapper.toJson(citations))
                    .build());

            emitter.send(SseEmitter.event()
                    .name("assistant_message")
                    .data(toMessageResponse(assistant)));
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    /**
     * Encode as a JSON string literal (quoted, newlines escaped). Sending a raw String would bypass
     * Jackson, break JSON.parse on the client, and split the SSE event on any newline in the text.
     */
    private String toJsonString(String value) {
        return jsonMapper.writeValueAsString(value);
    }

    private ChatMessageResponse toMessageResponse(ChatMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getRole(),
                message.getContent(),
                citationMapper.fromJson(message.getCitations()),
                message.getCreatedAt());
    }
}
