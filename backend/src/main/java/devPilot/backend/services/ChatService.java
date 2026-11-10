package devPilot.backend.services;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import devPilot.backend.config.DemoMode;
import devPilot.backend.dto.ChatMessageResponse;
import devPilot.backend.dto.ChatSessionResponse;
import devPilot.backend.dto.CreateChatSessionRequest;
import devPilot.backend.entity.ChatMessage;
import devPilot.backend.entity.ChatSession;
import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.MessageRole;
import devPilot.backend.entity.Repository;
import devPilot.backend.exceptions.BadRequestException;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.exceptions.TooManyRequestsException;
import devPilot.backend.repository.ChatMessageRepository;
import devPilot.backend.repository.ChatSessionRepository;
import devPilot.backend.services.ai.ChatPromptBuilder;
import devPilot.backend.services.ai.ChatStreamHandler;
import devPilot.backend.services.ai.CitationMapper;
import devPilot.backend.services.ai.CodeContextRetriever;
import lombok.RequiredArgsConstructor;

/**
 * Chat sessions and the RAG chat pipeline entry point.
 *
 * <p>{@link #streamReply} orchestrates the full flow:
 * validate → save user message → retrieve code context → build prompts → stream AI reply.
 * Each step is implemented in a dedicated class under {@code service.ai}.
 */
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final RepoService repoService;
    private final CodeContextRetriever codeContextRetriever;
    private final ChatPromptBuilder chatPromptBuilder;
    private final ChatStreamHandler chatStreamHandler;
    private final CitationMapper citationMapper;
    private final UserService userService;
    private final DemoMode demoMode;

    @Transactional
    public ChatSessionResponse createSession(UUID userId, CreateChatSessionRequest request) {
        Repository repo = repoService.requireReadable(request.repositoryId(), userId);
        if (repo.getIndexStatus() != IndexStatus.READY) {
            throw new BadRequestException("Repository must be indexed before chatting");
        }

        String title = request.title() != null && !request.title().isBlank()
                ? request.title()
                : "Chat with " + repo.getFullName();

        ChatSession session = ChatSession.builder()
                .userId(userId)
                .repositoryId(repo.getId())
                .title(title)
                .build();
        session = chatSessionRepository.save(session);
        return toSessionResponse(session);
    }

    @Transactional(readOnly = true)
    public List<ChatSessionResponse> listSessions(UUID userId, UUID repositoryId) {
        repoService.requireReadable(repositoryId, userId);
        return chatSessionRepository
                .findByUserIdAndRepositoryIdOrderByCreatedAtDesc(userId, repositoryId)
                .stream()
                .map(this::toSessionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getMessages(UUID userId, UUID sessionId) {
        ChatSession session = requireSession(userId, sessionId);
        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
                .map(this::toMessageResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ChatSession requireSession(UUID userId, UUID sessionId) {
        return chatSessionRepository.findByIdAndUserId(sessionId, userId)
                .orElseThrow(() -> new NotFoundException("Chat session not found"));
    }

    public SseEmitter streamReply(UUID userId, UUID sessionId, String userContent) {
        // 1. Ensure the session exists and the repo is indexed
        ChatSession session = requireSession(userId, sessionId);
        Repository repo = repoService.requireReadable(session.getRepositoryId(), userId);
        if (repo.getIndexStatus() != IndexStatus.READY) {
            throw new BadRequestException("Repository is not ready for chat");
        }
        enforceDemoLimits(userId);

        // 2. Persist the user's message
        ChatMessage userMessage = chatMessageRepository.save(ChatMessage.builder()
                .sessionId(session.getId())
                .role(MessageRole.USER)
                .content(userContent)
                .build());

        try {
            // 3. RAG retrieval — find code chunks similar to the question (embeds it: can be rate-limited)
            var retrievedContext = codeContextRetriever.retrieve(repo.getId(), userContent);

            // 4. Build LLM prompts from retrieved context + question
            String systemPrompt = chatPromptBuilder.systemPrompt(repo.getFullName());
            String userPrompt = chatPromptBuilder.userPrompt(retrievedContext.contextText(), userContent);

            // 5. Stream the LLM response to the client (SSE). If it fails, the handler removes
            //    the question itself.
            return chatStreamHandler.stream(
                    session.getId(),
                    toMessageResponse(userMessage),
                    retrievedContext.citations(),
                    systemPrompt,
                    userPrompt);
        } catch (RuntimeException ex) {
            // Failed before an answer could start: don't leave the question unanswered in the
            // thread or counted toward the visitor's question cap
            chatMessageRepository.deleteById(userMessage.getId());
            throw ex;
        }
    }

    /**
     * Demo mode only: caps how many questions a visitor (and all visitors together, per UTC day)
     * can ask, so a public portfolio link can't run up the LLM bill. Admins are exempt.
     */
    private void enforceDemoLimits(UUID userId) {
        if (!demoMode.isEnabled() || demoMode.canManageRepos(userService.requiredById(userId))) {
            return;
        }
        long mine = chatMessageRepository.countByRoleForUser(MessageRole.USER, userId);
        if (mine >= demoMode.maxMessagesPerUser()) {
            throw new TooManyRequestsException(
                    "You've reached the demo limit of " + demoMode.maxMessagesPerUser()
                            + " questions. Thanks for trying DevPilot!");
        }
        Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        long today = chatMessageRepository.countByRoleAndCreatedAtGreaterThanEqual(MessageRole.USER, startOfDay);
        if (today >= demoMode.maxMessagesPerDay()) {
            throw new TooManyRequestsException(
                    "The demo has hit its daily question limit. Please come back tomorrow.");
        }
    }

    private ChatSessionResponse toSessionResponse(ChatSession session) {
        return new ChatSessionResponse(
                session.getId(),
                session.getRepositoryId(),
                session.getTitle(),
                session.getCreatedAt());
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