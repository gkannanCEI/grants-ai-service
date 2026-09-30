package com.example.agent.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for {@link UserKnowledgeText}.
 */
@Repository
public interface UserKnowledgeTextRepository extends JpaRepository<UserKnowledgeText, String> {

    /** All text entries stored for a CID, newest first. */
    List<UserKnowledgeText> findByCidOrderByCreatedAtDesc(String cid);
}
