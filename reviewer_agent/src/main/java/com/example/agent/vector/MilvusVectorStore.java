package com.example.agent.vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.example.agent.config.AgentProperties;
import com.example.agent.exception.VectorStoreException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;

/**
 * Milvus v2 implementation of {@link VectorStore}.
 *
 * Active when: agent.vector.enabled=true (default).
 * Auto-creates the collection on first startup if it doesn't exist.
 *
 * Schema:
 *   id (VARCHAR PK), content (VARCHAR), embedding (FLOAT_VECTOR), source (VARCHAR)
 */
@Component
@ConditionalOnProperty(prefix = "agent.vector", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MilvusVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(MilvusVectorStore.class);

    private static final String FIELD_ID        = "id";
    private static final String FIELD_CONTENT   = "content";
    private static final String FIELD_EMBEDDING = "embedding";
    private static final String FIELD_SOURCE    = "source";

    private final AgentProperties.Vector config;
    private final Gson                   gson = new Gson();
    private MilvusClientV2               client;
    private boolean                      connected = false;

    public MilvusVectorStore(AgentProperties props) {
        this.config = props.getVector();
    }

    @PostConstruct
    public void init() {
        try {
            log.info("Connecting to Milvus — host={} port={}", config.getHost(), config.getPort());
            client = new MilvusClientV2(ConnectConfig.builder()
                    .uri("http://" + config.getHost() + ":" + config.getPort())
                    .build());
            ensureCollectionExists();
            connected = true;
            log.info("Milvus ready — collection={}", config.getCollection());
        } catch (Throwable t) {
            // Catch Throwable (not just Exception) — Milvus SDK can throw Errors
            // from gRPC/Netty initialization on connection failure.
            // We swallow it so the Spring context starts cleanly.
            connected = false;
            log.warn("Milvus unavailable — vector operations disabled. " +
                     "Start Milvus at {}:{} to enable. Reason: {}",
                     config.getHost(), config.getPort(), t.getMessage());
        }
    }

    // ── VectorStore implementation ───────────────────────────────────────────

    @Override
    public void upsert(List<VectorDocument> documents) {
        requireConnected();
        if (documents == null || documents.isEmpty()) return;
        try {
            // Milvus v2 InsertReq.data() requires List<JsonObject> (Gson)
            List<JsonObject> rows = new ArrayList<>(documents.size());
            for (VectorDocument doc : documents) {
                JsonObject row = new JsonObject();
                row.addProperty(FIELD_ID,      doc.id());
                row.addProperty(FIELD_CONTENT, doc.content());
                row.addProperty(FIELD_SOURCE,  doc.metadata() != null
                        ? doc.metadata().getOrDefault("source", "") : "");

                // Serialize the float list as a JSON array
                row.add(FIELD_EMBEDDING, gson.toJsonTree(doc.embedding()));
                rows.add(row);
            }

            client.insert(InsertReq.builder()
                    .collectionName(config.getCollection())
                    .data(rows)
                    .build());

            log.info("Upserted {} documents into collection={}", documents.size(), config.getCollection());
        } catch (Exception e) {
            throw new VectorStoreException("Upsert failed: " + e.getMessage(), e);
        }
    }

    @Override
    public List<VectorDocument> search(List<Float> queryEmbedding, int topK) {
        requireConnected();
        try {
            // Milvus v2 SearchReq.data() requires List<BaseVector> — wrap in FloatVec
            List<io.milvus.v2.service.vector.request.data.BaseVector> vectors =
                    List.of(new FloatVec(queryEmbedding));

            SearchResp resp = client.search(SearchReq.builder()
                    .collectionName(config.getCollection())
                    .data(vectors)
                    .annsField(FIELD_EMBEDDING)
                    .topK(topK)
                    .outputFields(List.of(FIELD_ID, FIELD_CONTENT, FIELD_SOURCE))
                    .build());

            List<VectorDocument> results = new ArrayList<>();
            for (List<SearchResp.SearchResult> group : resp.getSearchResults()) {
                for (SearchResp.SearchResult hit : group) {
                    Map<String, Object> entity = hit.getEntity();
                    String id      = String.valueOf(entity.getOrDefault(FIELD_ID,      ""));
                    String content = String.valueOf(entity.getOrDefault(FIELD_CONTENT, ""));
                    String source  = String.valueOf(entity.getOrDefault(FIELD_SOURCE,  ""));

                    results.add(new VectorDocument(
                            id,
                            content,
                            null,                        // embeddings not returned on search
                            Map.of("source", source),
                            (float) hit.getScore()
                    ));
                }
            }

            log.info("Vector search returned {} results (topK={})", results.size(), topK);
            return results;
        } catch (Exception e) {
            throw new VectorStoreException("Search failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String id) {
        requireConnected();
        try {
            client.delete(DeleteReq.builder()
                    .collectionName(config.getCollection())
                    .ids(List.of(id))
                    .build());
            log.info("Deleted id={} from collection={}", id, config.getCollection());
        } catch (Exception e) {
            throw new VectorStoreException("Delete failed for id=" + id + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean isHealthy() {
        return connected;
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void requireConnected() {
        if (!connected) {
            throw new VectorStoreException(
                "Milvus is not available. Start Milvus at " +
                config.getHost() + ":" + config.getPort() +
                " or set agent.vector.enabled=false to disable vector features.");
        }
    }

    private void ensureCollectionExists() {
        boolean exists = client.hasCollection(HasCollectionReq.builder()
                .collectionName(config.getCollection())
                .build());

        if (exists) {
            log.info("Collection already exists - {} - dropping and recreating to ensure dimension match (dim={})",
                    config.getCollection(), config.getDimension());
            // Drop and recreate to avoid dimension mismatch between old collection and current embedding model.
            // Safe on startup: if dimensions already match this is a no-op cost, if they don't it prevents OOM.
            client.dropCollection(
                    DropCollectionReq.builder()
                            .collectionName(config.getCollection())
                            .build());
            log.info("Dropped existing collection - {}", config.getCollection());
        }

        log.info("Creating collection — {} (dim={})", config.getCollection(), config.getDimension());

        CreateCollectionReq.CollectionSchema schema =
                CreateCollectionReq.CollectionSchema.builder().build();

        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_ID).dataType(DataType.VarChar)
                .isPrimaryKey(true).maxLength(256).build());

        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CONTENT).dataType(DataType.VarChar)
                .maxLength(65535).build());

        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_EMBEDDING).dataType(DataType.FloatVector)
                .dimension(config.getDimension()).build());

        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_SOURCE).dataType(DataType.VarChar)
                .maxLength(1024).build());

        IndexParam index = IndexParam.builder()
                .fieldName(FIELD_EMBEDDING)
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("nlist", 128))
                .build();

        client.createCollection(CreateCollectionReq.builder()
                .collectionName(config.getCollection())
                .collectionSchema(schema)
                .indexParams(List.of(index))
                .build());

        log.info("Collection created — {}", config.getCollection());
    }
}
