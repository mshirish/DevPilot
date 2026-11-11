package devPilot.backend.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.Repository;

public interface RepositoryRepository extends JpaRepository<Repository, UUID> {
    List<Repository> findByIndexStatus(IndexStatus indexStatus);

    List<Repository> findByUserIdOrderByFullNameAsc(UUID userId);

    Optional<Repository> findByIdAndUserId(UUID id, UUID userId);

    Optional<Repository> findByUserIdAndGithubRepoId(UUID userId, Long githubRepoId);

    /** Demo mode: preset repos (any owner) by lower-cased "owner/name". */
    @Query("select r from Repository r where lower(r.fullName) in :fullNames order by r.fullName")
    List<Repository> findByLowerFullNameIn(@Param("fullNames") Collection<String> fullNames);
}