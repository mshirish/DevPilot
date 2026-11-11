package devPilot.backend.dto;

import java.util.UUID;

public record UserResponse(
    UUID id,
    Long githubId,
    String githubUsername,
    String displayName,
    String avatarUrl,
    /** Demo-mode visitor without a GitHub account. */
    boolean guest,
    /** Whether this user may sync and index repos (false for demo visitors). */
    boolean canManageRepos
) {
}
