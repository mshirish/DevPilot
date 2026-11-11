package devPilot.backend;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import devPilot.backend.entity.ChatMessage;
import devPilot.backend.entity.ChatSession;
import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.MessageRole;
import devPilot.backend.entity.Repository;
import devPilot.backend.entity.User;
import devPilot.backend.repository.ChatMessageRepository;
import devPilot.backend.repository.ChatSessionRepository;
import devPilot.backend.repository.RepositoryRepository;
import devPilot.backend.repository.UserRepository;

/**
 * Chat errors raised before streaming starts (question caps, repo not ready) must reach the
 * browser as JSON, even though the endpoint produces text/event-stream. Caps are set to 1 so
 * the tests never reach OpenAI.
 */
@SpringBootTest(properties = {
        "app.demo.enabled=true",
        "app.demo.admin-github-usernames=limits-admin",
        "app.demo.repos=limits-admin/limits-repo",
        "app.demo.max-messages-per-user=1",
        "app.demo.max-messages-per-day=1"
})
class DemoChatLimitsIntegrationTests {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RepositoryRepository repositoryRepository;
    @Autowired
    private ChatSessionRepository chatSessionRepository;
    @Autowired
    private ChatMessageRepository chatMessageRepository;

    private MockMvc mvc;
    private Repository demoRepo;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        User admin = userRepository.save(User.builder()
                .githubId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
                .githubUsername("limits-admin")
                .displayName("Admin")
                .accessToken("unused")
                .build());
        demoRepo = repositoryRepository.save(Repository.builder()
                .userId(admin.getId())
                .githubRepoId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
                .owner("limits-admin")
                .name("limits-repo")
                .fullName("limits-admin/limits-repo")
                .defaultBranch("main")
                .indexStatus(IndexStatus.READY)
                .build());
    }

    @Test
    void questionCapsReturnJsonErrorsBeforeStreaming() throws Exception {
        MockHttpSession guest = guestSession();
        ChatSession chat = chatSessionRepository.save(ChatSession.builder()
                .userId(guestId(guest))
                .repositoryId(demoRepo.getId())
                .build());

        // Someone else already asked today's only question: the daily cap applies
        ChatSession other = chatSessionRepository.save(ChatSession.builder()
                .userId(demoRepo.getUserId())
                .repositoryId(demoRepo.getId())
                .build());
        chatMessageRepository.save(ChatMessage.builder()
                .sessionId(other.getId()).role(MessageRole.USER).content("hi").build());

        sendMessage(guest, chat.getId())
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(
                        "The demo has hit its daily question limit. Please come back tomorrow."))
                .andExpect(jsonPath("$.code").doesNotExist());

        // Once the guest has used their own question, the per-visitor cap applies first
        chatMessageRepository.save(ChatMessage.builder()
                .sessionId(chat.getId()).role(MessageRole.USER).content("hi").build());

        sendMessage(guest, chat.getId())
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(
                        "You've reached the demo limit of 1 questions. Thanks for trying DevPilot!"))
                .andExpect(jsonPath("$.code").value("DEMO_QUESTION_LIMIT"));
    }

    @Test
    void repoNotReadyReturnsJsonError() throws Exception {
        MockHttpSession guest = guestSession();
        ChatSession chat = chatSessionRepository.save(ChatSession.builder()
                .userId(guestId(guest))
                .repositoryId(demoRepo.getId())
                .build());
        demoRepo.setIndexStatus(IndexStatus.INDEXING);
        repositoryRepository.save(demoRepo);

        sendMessage(guest, chat.getId())
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("Repository is not ready for chat"));
    }

    private org.springframework.test.web.servlet.ResultActions sendMessage(
            MockHttpSession session, UUID chatId) throws Exception {
        // Same headers the browser's fetch() sends (no explicit Accept, so */*)
        return mvc.perform(post("/api/chat/sessions/{id}/messages", chatId)
                .session(session)
                .accept(MediaType.ALL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"How does login work?\"}"));
    }

    private MockHttpSession guestSession() throws Exception {
        return (MockHttpSession) mvc.perform(post("/api/auth/demo"))
                .andExpect(status().isOk())
                .andReturn().getRequest().getSession(false);
    }

    private UUID guestId(MockHttpSession session) {
        var ctx = (org.springframework.security.core.context.SecurityContext) session.getAttribute(
                org.springframework.security.web.context.HttpSessionSecurityContextRepository
                        .SPRING_SECURITY_CONTEXT_KEY);
        return ((devPilot.backend.security.AppUserPrincipal) ctx.getAuthentication().getPrincipal()).getId();
    }
}
