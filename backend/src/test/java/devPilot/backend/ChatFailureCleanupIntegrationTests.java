package devPilot.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import devPilot.backend.entity.ChatSession;
import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.MessageRole;
import devPilot.backend.entity.Repository;
import devPilot.backend.entity.User;
import devPilot.backend.repository.ChatMessageRepository;
import devPilot.backend.repository.ChatSessionRepository;
import devPilot.backend.repository.RepositoryRepository;
import devPilot.backend.repository.UserRepository;
import devPilot.backend.security.AppUserPrincipal;
import devPilot.backend.services.ai.AiErrors;
import devPilot.backend.services.ai.AiErrorsTests;
import reactor.core.publisher.Flux;

/**
 * When the AI call fails before an answer is saved, the visitor's question is removed: it must not
 * stay unanswered in the thread or count toward their question cap. The AI provider is mocked.
 */
@SpringBootTest(properties = {
        "app.demo.enabled=true",
        "app.demo.admin-github-usernames=cleanup-admin",
        "app.demo.repos=cleanup-admin/cleanup-repo",
        "app.demo.max-messages-per-user=1",
        "app.demo.max-messages-per-day=1000000"
})
class ChatFailureCleanupIntegrationTests {

    @MockitoBean
    private VectorStore vectorStore;
    @MockitoBean
    private ChatModel chatModel;

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
                .githubUsername("cleanup-admin")
                .displayName("Admin")
                .accessToken("unused")
                .build());
        demoRepo = repositoryRepository.save(Repository.builder()
                .userId(admin.getId())
                .githubRepoId(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE))
                .owner("cleanup-admin")
                .name("cleanup-repo")
                .fullName("cleanup-admin/cleanup-repo")
                .defaultBranch("main")
                .indexStatus(IndexStatus.READY)
                .build());
    }

    @Test
    void questionIsRemovedWhenEmbeddingItIsRateLimited() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(AiErrorsTests.rateLimit("per-minute limit"));
        MockHttpSession guest = guestSession();
        UUID chatId = newChat(guest);

        sendMessage(guest, chatId)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.message").value(AiErrors.RATE_LIMITED_MESSAGE));

        assertThat(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(chatId)).isEmpty();
        assertThat(chatMessageRepository.countByRoleForUser(MessageRole.USER, guestId(guest))).isZero();
    }

    @Test
    void questionIsRemovedWhenStreamedAnswerFails() throws Exception {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        when(chatModel.getOptions()).thenReturn(ChatOptions.builder().build());
        when(chatModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(AiErrorsTests.rateLimit("per-minute limit")));
        MockHttpSession guest = guestSession();
        UUID chatId = newChat(guest);

        MvcResult started = sendMessage(guest, chatId)
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = mvc.perform(asyncDispatch(started)).andReturn().getResponse().getContentAsString();

        assertThat(body).contains("event:error").contains(AiErrors.RATE_LIMITED_MESSAGE);
        assertThat(chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(chatId)).isEmpty();
        // The failed question didn't use up the visitor's single question: they can try again
        assertThat(chatMessageRepository.countByRoleForUser(MessageRole.USER, guestId(guest))).isZero();
        sendMessage(guest, chatId).andExpect(request().asyncStarted());
    }

    private org.springframework.test.web.servlet.ResultActions sendMessage(
            MockHttpSession session, UUID chatId) throws Exception {
        return mvc.perform(post("/api/chat/sessions/{id}/messages", chatId)
                .session(session)
                .accept(MediaType.ALL)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"How does login work?\"}"));
    }

    private UUID newChat(MockHttpSession guest) {
        return chatSessionRepository.save(ChatSession.builder()
                .userId(guestId(guest))
                .repositoryId(demoRepo.getId())
                .build()).getId();
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
        return ((AppUserPrincipal) ctx.getAuthentication().getPrincipal()).getId();
    }
}
