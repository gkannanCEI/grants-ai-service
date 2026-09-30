package com.example.agent.reservation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.agent.exception.AgentException;
import com.example.agent.llm.ChatClient;
import com.example.agent.persistence.ReservationSummary;
import com.example.agent.persistence.ReservationSummaryRepository;
import com.example.agent.retrieval.RetrievalService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Supplemental question-answering agent scoped to a single reservation, optionally
 * personalized to a logged-in user (CID).
 *
 * <p>Answers a follow-up question using up to three context sources:
 * <ol>
 *   <li>the stored AI summary ({@code reservation_summary}),</li>
 *   <li>the reservation's indexed documents, retrieved via RAG
 *       ({@link RetrievalService}, scoped to the reservation ID as applicationId), and</li>
 *   <li>when a CID is supplied, that user's personal knowledge base — plain text and
 *       uploaded documents registered via {@link UserKnowledgeService} — retrieved via
 *       the same RAG mechanism, scoped to the CID.</li>
 * </ol>
 * The optional hint is passed to the model as extra guidance on how to interpret
 * or where to look for the answer. The model is instructed to answer only from
 * the provided context.
 */
@Service
public class SupplementalQaService {

    private static final Logger log = LoggerFactory.getLogger(SupplementalQaService.class);

    private static final String INSTRUCTION = """
            You are a supplemental question-answering assistant for a single grant reservation,
            personalized for the logged-in user when their knowledge base is available.

            Answer the user's question using ONLY the context provided below, which consists of:
              (1) an AI-generated SUMMARY of the reservation,
              (2) excerpts from the reservation's INDEXED DOCUMENTS, each preceded by a
                  bracketed label like [filename.pdf#2], and
              (3) when available, the logged-in USER'S PERSONAL KNOWLEDGE (their own notes and
                  documents), also labeled — use this to tailor the answer to that specific user.

            Rules:
            - Base your answer strictly on the provided summary, document excerpts, and user
              knowledge. Prefer the user's personal knowledge when it directly answers the
              question and the reservation context does not conflict with it.
            - If a HINT is provided, use it to guide how you interpret the question or where to
              look, but do not treat the hint as a fact unless the context supports it.
            - If the answer cannot be found in the context, say clearly that the information is
              not available. Do NOT invent an answer.
            - Be concise and specific; preserve exact values (names, numbers, dates, amounts, IDs).

            Respond with ONLY a single JSON object (no markdown, no code fences, no extra text),
            matching exactly this shape:
            {
              "answer": "<the answer text>",
              "reasoning": "<one short sentence on why this answer follows from the context>",
              "sources": [
                { "type": "SUMMARY" | "DOCUMENT" | "USER_KNOWLEDGE" | "HINT", "label": "<the bracketed label you relied on, or omit label for SUMMARY/HINT>" }
              ]
            }
            List every label you actually relied on in "sources". If nothing in the context was
            usable, return an empty "sources" array. Keep "reasoning" under 20 words.
            """;

    private final ReservationSummaryRepository summaryRepository;
    private final RetrievalService             retrievalService;
    private final ChatClient                   chatClient;
    private final ObjectMapper                 mapper = new ObjectMapper();

    public SupplementalQaService(ReservationSummaryRepository summaryRepository,
                                 RetrievalService retrievalService,
                                 ChatClient chatClient) {
        this.summaryRepository = summaryRepository;
        this.retrievalService  = retrievalService;
        this.chatClient        = chatClient;
    }

    /**
     * Answers a collection of supplemental questions about a reservation, optionally
     * personalized to a logged-in user.
     *
     * <p>The reservation's stored AI summary is loaded once and reused for every
     * question. Document retrieval (reservation + personal knowledge base) is
     * performed per question, because the most relevant excerpts depend on the
     * specific question being asked.
     *
     * @param reservationId the reservation providing the context
     * @param cid           the logged-in user's identifier, or null/blank to skip
     *                      personal knowledge and use only reservation context
     * @param questions     the questions to answer (each with an optional hint)
     * @return one answer per question, in the same order
     */
    @Transactional(readOnly = true)
    public List<Answer> answerAll(String reservationId, String cid, List<Question> questions) {
        log.info("Supplemental QA batch — reservationId={} cid={} questions={}",
                reservationId, cid, questions.size());

        // Latest stored AI summary for the reservation (loaded once, reused).
        String summaryText = summaryRepository
                .findByReservationIdOrderByCreatedAtDesc(reservationId)
                .stream()
                .map(ReservationSummary::getSummary)
                .filter(s -> s != null && !s.isBlank())
                .findFirst()
                .orElse(null);

        List<Answer> answers = new java.util.ArrayList<>(questions.size());
        for (Question q : questions) {
            answers.add(answerOne(reservationId, cid, summaryText, q.question(), q.hint()));
        }
        return answers;
    }

    /** Answers a single question against the already-loaded reservation summary. */
    private Answer answerOne(String reservationId, String cid, String summaryText,
                             String question, String hint) {
        // Relevant document excerpts for THIS question (RAG scoped to the reservation).
        RetrievalService.RetrievalResult retrieval =
                retrievalService.retrieve(reservationId, question);
        String documentContext = retrieval.hasContext() ? retrieval.contextBlock() : null;

        // The logged-in user's personal knowledge base (RAG scoped to the CID),
        // using the SAME retrieval mechanism — a CID is just another scoping key.
        boolean hasCid = cid != null && !cid.isBlank();
        RetrievalService.RetrievalResult userRetrieval = hasCid
                ? retrievalService.retrieve(cid, question)
                : RetrievalService.RetrievalResult.empty();
        String userContext = userRetrieval.hasContext() ? userRetrieval.contextBlock() : null;

        if (summaryText == null && documentContext == null && userContext == null) {
            log.info("No context available for reservationId={} cid={} question=\"{}\"",
                    reservationId, cid, question);
            return new Answer(question,
                    "No context is available for reservation " + reservationId
                            + ". There is no stored summary, no indexed documents, and no "
                            + "personal knowledge to answer from.",
                    false, false, 0, false, 0, null, List.of());
        }

        String prompt = buildPrompt(reservationId, question, hint, summaryText,
                documentContext, userContext);
        String rawReply = chatClient.complete(INSTRUCTION, prompt);

        if (rawReply == null || rawReply.isBlank()) {
            throw new AgentException("LLM returned an empty answer for question: " + question);
        }

        log.info("Supplemental QA answered — reservationId={} cid={} usedSummary={} docChunks={} "
                        + "userKnowledgeChunks={}",
                reservationId, cid, summaryText != null, retrieval.chunkCount(),
                userRetrieval.chunkCount());

        // Chunks from both retrieval passes, keyed by the same label cited in the prompt,
        // so a citation can be resolved back to its exact (non-hallucinated) chunk text.
        Map<String, RetrievalService.RetrievedChunk> chunksByLabel = new java.util.HashMap<>();
        retrieval.chunks().forEach(c -> chunksByLabel.put(c.label(), c));
        userRetrieval.chunks().forEach(c -> chunksByLabel.put(c.label(), c));

        ParsedReply parsed = parseReply(rawReply, hint, chunksByLabel);

        return new Answer(question, parsed.answer(),
                summaryText != null, documentContext != null, retrieval.chunkCount(),
                userContext != null, userRetrieval.chunkCount(),
                parsed.reasoning(), parsed.sources());
    }

    /**
     * Parses the model's JSON reply into an answer, short reasoning, and resolved source
     * citations. Falls back to treating the whole reply as the plain answer text (no
     * reasoning/sources) if it isn't valid JSON in the expected shape — the feature is
     * additive and must never break answering.
     */
    private ParsedReply parseReply(String rawReply, String hint,
                                   Map<String, RetrievalService.RetrievedChunk> chunksByLabel) {
        String cleaned = stripCodeFence(rawReply.trim());
        try {
            JsonNode root = mapper.readTree(cleaned);
            String answerText = root.path("answer").asText("").trim();
            if (answerText.isEmpty()) {
                return new ParsedReply(rawReply.trim(), null, List.of());
            }

            String reasoning = root.path("reasoning").asText(null);
            if (reasoning != null && reasoning.isBlank()) {
                reasoning = null;
            }

            List<SourceRef> sources = new ArrayList<>();
            JsonNode sourcesNode = root.path("sources");
            if (sourcesNode.isArray()) {
                for (JsonNode s : sourcesNode) {
                    String type = s.path("type").asText("").toUpperCase(java.util.Locale.ROOT);
                    String label = s.path("label").asText(null);
                    sources.add(resolveSource(type, label, hint, chunksByLabel));
                }
            }
            return new ParsedReply(answerText, reasoning, sources);
        } catch (Exception e) {
            // Not valid JSON — treat the raw text as the answer, exactly like before this
            // feature existed. Reviewers simply won't see a "Why?" expander for this answer.
            log.debug("Supplemental QA reply was not structured JSON, using as plain text: {}",
                    e.getMessage());
            return new ParsedReply(rawReply.trim(), null, List.of());
        }
    }

    /** Resolves a cited label back to a precise, verbatim snippet (never LLM-paraphrased). */
    private SourceRef resolveSource(String type, String label, String hint,
                                    Map<String, RetrievalService.RetrievedChunk> chunksByLabel) {
        RetrievalService.RetrievedChunk chunk = label != null ? chunksByLabel.get(label) : null;
        if (chunk != null) {
            String resolvedType = "USER_KNOWLEDGE".equals(type) ? "USER_KNOWLEDGE" : "DOCUMENT";
            return new SourceRef(resolvedType, chunk.label(), snippetOf(chunk.content()));
        }
        if ("HINT".equals(type)) {
            return new SourceRef("HINT", "Custom AI instruction", snippetOf(hint));
        }
        // SUMMARY, or a DOCUMENT/USER_KNOWLEDGE label the model cited that we couldn't resolve
        // to an actual retrieved chunk (e.g. it paraphrased the label) — still report a type
        // so the UI can show *something* precise about where it looked.
        boolean isUserKnowledge = "USER_KNOWLEDGE".equals(type);
        return new SourceRef(isUserKnowledge ? "USER_KNOWLEDGE" : "SUMMARY",
                isUserKnowledge ? "User's personal knowledge" : "Reservation AI summary", null);
    }

    /** Trims a snippet to a short, precise excerpt — not the full chunk. */
    private static String snippetOf(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 180 ? trimmed : trimmed.substring(0, 177) + "...";
    }

    /** Strips ```json ... ``` / ``` ... ``` fences some models wrap JSON replies in. */
    private static String stripCodeFence(String s) {
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        return s.trim();
    }

    private record ParsedReply(String answer, String reasoning, List<SourceRef> sources) { }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String buildPrompt(String reservationId, String question, String hint,
                               String summaryText, String documentContext, String userContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("Reservation: ").append(reservationId).append("\n\n");

        sb.append("=== RESERVATION SUMMARY ===\n");
        sb.append(summaryText != null ? summaryText : "(no summary available)").append("\n\n");

        sb.append("=== INDEXED DOCUMENT EXCERPTS ===\n");
        sb.append(documentContext != null ? documentContext
                : "(no relevant document excerpts found)").append("\n\n");

        if (userContext != null) {
            sb.append("=== USER'S PERSONAL KNOWLEDGE ===\n");
            sb.append(userContext).append("\n\n");
        }

        if (hint != null && !hint.isBlank()) {
            sb.append("=== HINT ===\n").append(hint.trim()).append("\n\n");
        }

        sb.append("=== QUESTION ===\n").append(question.trim()).append('\n');
        return sb.toString();
    }

    /** A single supplemental question with an optional hint. */
    public record Question(String question, String hint) { }

    /**
     * The answer to one question plus provenance about which context was used.
     *
     * @param question           the question that was asked
     * @param answer              the model's answer
     * @param usedSummary         whether the stored summary was available and included
     * @param usedDocuments       whether reservation document excerpts were used
     * @param documentChunks      number of reservation document chunks used as context
     * @param usedUserKnowledge   whether the user's (CID) personal knowledge base was used
     * @param userKnowledgeChunks number of personal knowledge chunks used as context
     * @param reasoning           short (&lt;20 words) explanation of why this answer follows
     *                            from the context, or null if the model reply wasn't
     *                            structured JSON (older/non-conforming model output)
     * @param sources             precise citations the answer was derived from (each with a
     *                            type, a short label, and — where resolvable — a verbatim
     *                            snippet of the actual source text); empty when none apply
     */
    public record Answer(String question, String answer,
                          boolean usedSummary, boolean usedDocuments, int documentChunks,
                          boolean usedUserKnowledge, int userKnowledgeChunks,
                          String reasoning, List<SourceRef> sources) {
    }

    /**
     * A precise citation for one piece of context the answer relied on.
     *
     * @param type    where this came from: SUMMARY, DOCUMENT, USER_KNOWLEDGE, or HINT
     * @param label   short identifier, e.g. "invoice.pdf#2" or "Reservation AI summary"
     * @param snippet verbatim excerpt (≤180 chars) of the actual source text, or null
     *                when a precise snippet isn't available (e.g. SUMMARY/HINT sources)
     */
    public record SourceRef(String type, String label, String snippet) { }
}
