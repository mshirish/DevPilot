package devPilot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import devPilot.backend.entity.User;

class DemoModeTests {

    private static User user(String username, boolean guest) {
        return User.builder().githubUsername(username).guest(guest).build();
    }

    @Test
    void disabledDemoModeLetsEveryoneManageTheirRepos() {
        DemoMode demo = new DemoMode(false, "", "", 20, 300);
        assertThat(demo.canManageRepos(user("anyone", false))).isTrue();
    }

    @Test
    void onlyAdminsManageReposInDemoMode() {
        DemoMode demo = new DemoMode(true, " Alice , bob", "Alice/DevPilot", 20, 300);
        assertThat(demo.canManageRepos(user("alice", false))).isTrue();
        assertThat(demo.canManageRepos(user("BOB", false))).isTrue();
        assertThat(demo.canManageRepos(user("mallory", false))).isFalse();
        assertThat(demo.canManageRepos(user("alice", true))).isFalse();
    }

    @Test
    void demoRepoMatchingIsCaseInsensitive() {
        DemoMode demo = new DemoMode(true, "alice", "Alice/DevPilot, alice/other ", 20, 300);
        assertThat(demo.isDemoRepo("alice/devpilot")).isTrue();
        assertThat(demo.isDemoRepo("ALICE/OTHER")).isTrue();
        assertThat(demo.isDemoRepo("alice/secret")).isFalse();
        assertThat(demo.repoFullNames()).containsExactly("alice/devpilot", "alice/other");
    }
}
