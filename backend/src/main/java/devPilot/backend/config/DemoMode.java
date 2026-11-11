package devPilot.backend.config;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import devPilot.backend.entity.User;

/**
 * Portfolio "demo mode" settings.
 *
 * <p>When enabled:
 * <ul>
 *   <li>Visitors can sign in as a guest (no GitHub account needed).</li>
 *   <li>Only the preset repos in {@code app.demo.repos} are visible to non-admins.</li>
 *   <li>Only admins (by GitHub username) can sync or index repos, and only the preset ones.</li>
 *   <li>Chat messages are capped per user and per day to keep LLM costs bounded.</li>
 * </ul>
 * When disabled, the app behaves exactly as before: every user works with their own repos.
 */
@Component
public class DemoMode {

    private final boolean enabled;
    private final Set<String> adminUsernames;
    private final List<String> repoFullNames;
    private final int maxMessagesPerUser;
    private final int maxMessagesPerDay;

    public DemoMode(
            @Value("${app.demo.enabled:false}") boolean enabled,
            @Value("${app.demo.admin-github-usernames:}") String adminUsernames,
            @Value("${app.demo.repos:}") String repoFullNames,
            @Value("${app.demo.max-messages-per-user:20}") int maxMessagesPerUser,
            @Value("${app.demo.max-messages-per-day:200}") int maxMessagesPerDay) {
        this.enabled = enabled;
        this.adminUsernames = Set.copyOf(splitLower(adminUsernames));
        this.repoFullNames = List.copyOf(splitLower(repoFullNames));
        this.maxMessagesPerUser = maxMessagesPerUser;
        this.maxMessagesPerDay = maxMessagesPerDay;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Admins can sync and index. Outside demo mode, everyone manages their own repos. */
    public boolean canManageRepos(User user) {
        if (!enabled) {
            return true;
        }
        return !user.isGuest()
                && user.getGithubUsername() != null
                && adminUsernames.contains(user.getGithubUsername().toLowerCase(Locale.ROOT));
    }

    public boolean isDemoRepo(String fullName) {
        return fullName != null && repoFullNames.contains(fullName.toLowerCase(Locale.ROOT));
    }

    /** Lower-cased "owner/name" values, in the order they were configured. */
    public List<String> repoFullNames() {
        return repoFullNames;
    }

    public int maxMessagesPerUser() {
        return maxMessagesPerUser;
    }

    public int maxMessagesPerDay() {
        return maxMessagesPerDay;
    }

    private static List<String> splitLower(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .distinct()
                .collect(Collectors.toList());
    }
}
