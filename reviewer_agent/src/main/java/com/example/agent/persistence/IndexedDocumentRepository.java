package com.example.agent.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link IndexedDocument}.
 */
@Repository
public interface IndexedDocumentRepository extends JpaRepository<IndexedDocument, String> {

    /** All documents filed under an applicationId, newest first. */
    List<IndexedDocument> findByApplicationIdOrderByCreatedAtDesc(String applicationId);

    /** Lookup by the source endpoint URL (used for de-duplication). */
    List<IndexedDocument> findBySourceUrl(String sourceUrl);

    /** True if a successfully indexed record already exists for this source URL + content hash. */
    boolean existsBySourceUrlAndContentHashAndStatus(String sourceUrl, String contentHash, DocumentStatus status);

    /**
     * True if a successfully indexed record already exists for this application +
     * source + content hash. De-dup is scoped per application, so the same file
     * ingested under a different applicationId is treated as new.
     */
    boolean existsByApplicationIdAndSourceUrlAndContentHashAndStatus(
            String applicationId, String sourceUrl, String contentHash, DocumentStatus status);

    Optional<IndexedDocument> findFirstBySourceUrlAndContentHash(String sourceUrl, String contentHash);
}
