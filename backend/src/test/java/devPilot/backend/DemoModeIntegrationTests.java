package devPilot.backend;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Boots the full app against a real Postgres (see .github/workflows/ci.yml) with demo mode on,
 * and checks the guest flow end to end: sign in, session works, read-only rules apply.
 */
@SpringBootTest(properties = {
        "app.demo.enabled=true",
        "app.demo.admin-github-usernames=demo-admin",
        "app.demo.repos=demo-admin/sample-repo",
        "app.ai.provider-name= Test Provider "
})
class DemoModeIntegrationTests {

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void configIsPublic() throws Exception {
        mvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demoMode").value(true))
                .andExpect(jsonPath("$.demoRepos[0]").value("demo-admin/sample-repo"))
                .andExpect(jsonPath("$.aiProviderName").value("Test Provider"));
    }

    @Test
    void meRequiresSignIn() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void guestCanSignInAndIsReadOnly() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/demo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guest").value(true))
                .andExpect(jsonPath("$.canManageRepos").value(false))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);

        // The session from the guest login authenticates later requests
        mvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guest").value(true));

        // Signing in again keeps the same guest
        mvc.perform(post("/api/auth/demo").session(session))
                .andExpect(status().isOk());

        // Visitors see only preset repos (none indexed in this test DB) and never hit GitHub
        mvc.perform(get("/api/repos?refresh=true").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // Indexing is blocked for visitors
        mvc.perform(post("/api/repos/{id}/index", UUID.randomUUID()).session(session))
                .andExpect(status().isForbidden());

        // Other users' repos stay hidden
        mvc.perform(get("/api/repos/{id}", UUID.randomUUID()).session(session))
                .andExpect(status().isNotFound());
    }
}
