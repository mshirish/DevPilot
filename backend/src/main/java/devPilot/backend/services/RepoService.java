package devPilot.backend.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import devPilot.backend.config.DemoMode;
import devPilot.backend.dto.IndexStatusResponse;
import devPilot.backend.dto.RepositoryResponse;
import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.Repository;
import devPilot.backend.entity.User;
import devPilot.backend.exceptions.BadRequestException;
import devPilot.backend.exceptions.ForbiddenException;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.repository.RepositoryRepository;
import devPilot.backend.repository.UserRepository;
import devPilot.backend.services.github.GithubApiClient;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RepoService {
    private final RepositoryRepository repositoryRepository;
    private final UserService userService;
    private final GithubApiClient gitHubApiClient;
    private final UserRepository userRepository;
    private final DemoMode demoMode;

    @Transactional
    public List<RepositoryResponse> syncAndListRepos(UUID userId) {
        User user = userService.requiredById(userId);
        String token = userService.decryptAccessToken(user);
        List<Map<String, Object>> remoteRepos = gitHubApiClient.listUserRepos(token);

        List<Repository> saved = new ArrayList<>();

          for (Map<String, Object> remote : remoteRepos) {
            Long githubRepoId = toLong(remote.get("id"));
            Repository repo = repositoryRepository
                    .findByUserIdAndGithubRepoId(userId, githubRepoId)
                    .orElseGet(Repository::new);

            String fullName = String.valueOf(remote.get("full_name"));
            String[] parts = fullName.split("/", 2);

            repo.setUserId(userId);
            repo.setGithubRepoId(githubRepoId);
            repo.setOwner(parts.length > 0 ? parts[0] : String.valueOf(remote.get("owner")));
            repo.setName(parts.length > 1 ? parts[1] : String.valueOf(remote.get("name")));
            repo.setFullName(fullName);
            repo.setPrivate(Boolean.TRUE.equals(remote.get("private")));
            repo.setDefaultBranch(remote.get("default_branch") != null
                    ? String.valueOf(remote.get("default_branch"))
                    : "main");
            repo.setLanguage(remote.get("language") != null ? String.valueOf(remote.get("language")) : null);
            repo.setHtmlUrl(remote.get("html_url") != null ? String.valueOf(remote.get("html_url")) : null);
            repo.setDescription(remote.get("description") != null ? String.valueOf(remote.get("description")) : null);
            repo.setUpdatedAt(Instant.now());
            if (repo.getOwner() == null || repo.getOwner().isBlank()) {
                Object ownerObj = remote.get("owner");
                if (ownerObj instanceof Map<?, ?> ownerMap && ownerMap.get("login") != null) {
                    repo.setOwner(String.valueOf(ownerMap.get("login")));
                }
            }
            saved.add(repositoryRepository.save(repo));
        }
    
         return saved.stream()
                .sorted((a, b) -> a.getFullName().compareToIgnoreCase(b.getFullName()))
                .map(this::toResponse)
                .toList();
    }

    
    @Transactional(readOnly = true)
    public List<RepositoryResponse> listStored(UUID userId) {
        return repositoryRepository.findByUserIdOrderByFullNameAsc(userId).stream()
                .map(this::toResponse)
                .toList();
    }
    
    @Transactional(readOnly = true)
    public Repository requireOwned(UUID repoId, UUID userId) {
        return repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
    }

    /**
     * Repo list for the dashboard. In demo mode, visitors (anyone who can't manage repos)
     * see only the preset demo repos and never trigger a GitHub sync.
     */
    @Transactional
    public List<RepositoryResponse> listForUser(UUID userId, boolean refresh) {
        User user = userService.requiredById(userId);
        if (!demoMode.canManageRepos(user)) {
            return listDemoRepos();
        }
        return refresh ? syncAndListRepos(userId) : listStored(userId);
    }

    /** Preset demo repos, as synced/indexed by an admin. One entry per full name. */
    @Transactional(readOnly = true)
    public List<RepositoryResponse> listDemoRepos() {
        if (demoMode.repoFullNames().isEmpty()) {
            return List.of();
        }
        Map<String, Repository> byName = new LinkedHashMap<>();
        for (Repository repo : repositoryRepository.findByLowerFullNameIn(demoMode.repoFullNames())) {
            if (!isAdminOwned(repo)) {
                continue;
            }
            String key = repo.getFullName().toLowerCase(Locale.ROOT);
            Repository existing = byName.get(key);
            // If two admins synced the same repo, prefer the indexed copy
            if (existing == null
                    || (existing.getIndexStatus() != IndexStatus.READY && repo.getIndexStatus() == IndexStatus.READY)) {
                byName.put(key, repo);
            }
        }
        return byName.values().stream().map(this::toResponse).toList();
    }

    /**
     * Read access: the user's own repo, or (in demo mode) a preset demo repo owned by an admin.
     * Use this for viewing and chatting; use {@link #requireManageable} for sync/index.
     */
    @Transactional(readOnly = true)
    public Repository requireReadable(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        if (repo.getUserId().equals(userId)) {
            return repo;
        }
        if (demoMode.isEnabled() && demoMode.isDemoRepo(repo.getFullName()) && isAdminOwned(repo)) {
            return repo;
        }
        throw new NotFoundException("Repository not found");
    }

    /** Write access (indexing). In demo mode: admins only, and only the preset repos. */
    @Transactional(readOnly = true)
    public Repository requireManageable(UUID repoId, UUID userId) {
        User user = userService.requiredById(userId);
        if (!demoMode.canManageRepos(user)) {
            throw new ForbiddenException("This is a read-only demo. Indexing is disabled.");
        }
        Repository repo = requireOwned(repoId, userId);
        if (demoMode.isEnabled() && !demoMode.isDemoRepo(repo.getFullName())) {
            throw new BadRequestException(
                    "Demo mode only indexes the preset repos (DEMO_REPOS). Add " + repo.getFullName() + " there first.");
        }
        return repo;
    }

    private boolean isAdminOwned(Repository repo) {
        return userRepository.findById(repo.getUserId())
                .map(demoMode::canManageRepos)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public IndexStatusResponse status(UUID repoId, UUID userId) {
        Repository repo = requireReadable(repoId, userId);
        return new IndexStatusResponse(
                repo.getId(),
                repo.getIndexStatus(),
                repo.getFilesTotal(),
                repo.getFilesProcessed(),
                repo.getChunkCount(),
                repo.getIndexedAt(),
                repo.getErrorMessage());
    }

     public RepositoryResponse toResponse(Repository repo) {
        return new RepositoryResponse(
                repo.getId(),
                repo.getGithubRepoId(),
                repo.getOwner(),
                repo.getName(),
                repo.getFullName(),
                repo.isPrivate(),
                repo.getDefaultBranch(),
                repo.getLanguage(),
                repo.getHtmlUrl(),
                repo.getDescription(),
                repo.getIndexStatus(),
                repo.getIndexedAt(),
                repo.getChunkCount(),
                repo.getFilesTotal(),
                repo.getFilesProcessed(),
                repo.getErrorMessage());
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}