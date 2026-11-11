package devPilot.backend.services;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import devPilot.backend.entity.User;
import devPilot.backend.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final TextEncryptor tokenEncryptor;
    
   @Transactional
    public User upsertFromGitHub(Map<String, Object> attributes, String accessToken, String scopes) {
        Long githubId = toLong(attributes.get("id"));
        String login = String.valueOf(attributes.get("login"));
        String name = attributes.get("name") != null
                ? String.valueOf(attributes.get("name"))
                : login;
        String avatarUrl = attributes.get("avatar_url") != null
                ? String.valueOf(attributes.get("avatar_url"))
                : null;

        String encryptedToken = tokenEncryptor.encrypt(accessToken);

        User user = userRepository.findByGithubId(githubId).orElseGet(User::new);
        user.setGithubId(githubId);
        user.setGithubUsername(login);
        user.setDisplayName(name);
        user.setAvatarUrl(avatarUrl);
        user.setAccessToken(encryptedToken);
        user.setTokenScopes(scopes);
        return userRepository.save(user);
    }
    /**
     * Creates a throwaway demo visitor. Guests have no GitHub account, so they get a negative
     * placeholder githubId (real GitHub ids are positive) and an empty token that is never used.
     */
    @Transactional
    public User createGuest() {
        long placeholderId = -ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        String suffix = Long.toHexString(-placeholderId);
        suffix = suffix.substring(Math.max(0, suffix.length() - 6));
        User guest = User.builder()
                .githubId(placeholderId)
                .githubUsername("guest-" + suffix)
                .displayName("Demo visitor")
                .accessToken("")
                .guest(true)
                .build();
        return userRepository.save(guest);
    }

    @Transactional(readOnly = true)
    public User requiredById(UUID id) {
        return userRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("User not found"));
    }

    public String decryptAccessToken(User user) {
        if (user.isGuest()) {
            throw new IllegalStateException("Guest users have no GitHub access token");
        }
        return tokenEncryptor.decrypt(user.getAccessToken());
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

   

}