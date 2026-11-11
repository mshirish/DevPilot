package devPilot.backend.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import devPilot.backend.entity.ChatMessage;
import devPilot.backend.entity.MessageRole;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /** Demo-mode daily cap: messages of a role sent since a point in time, across all users. */
    long countByRoleAndCreatedAtGreaterThanEqual(MessageRole role, Instant since);

    /** Demo-mode per-user cap: messages of a role across all of a user's chat sessions. */
    @Query("select count(m) from ChatMessage m where m.role = :role "
            + "and m.sessionId in (select s.id from ChatSession s where s.userId = :userId)")
    long countByRoleForUser(@Param("role") MessageRole role, @Param("userId") UUID userId);
}