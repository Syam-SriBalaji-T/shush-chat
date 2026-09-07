package site.syamdev.shush.matching;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Scores waiting users by how well their interests overlap the searcher's.
 *
 * <p>Elasticsearch rather than embeddings, because this is term overlap over a controlled
 * vocabulary — a lexical retrieval problem, which is what an inverted index is for. Both sides
 * draw from the same fixed tag list, so there is no semantic gap for vectors to close, and
 * approximate nearest-neighbour search would add an embedding model to the request path to
 * approximate a set intersection that can be computed exactly. That calculus flips the moment
 * the input stops being a controlled vocabulary — free-text bios, or "find users like this
 * user" derived from behaviour — and that is where pgvector becomes the right answer instead.
 *
 * <p>The index also does the filtering in the same query: exclude yourself, anyone you have
 * blocked or who has blocked you, and anyone already a friend.
 */
@Component
public class WaitingIndex {

    private static final Logger log = LoggerFactory.getLogger(WaitingIndex.class);

    private final ElasticsearchClient elasticsearch;

    /**
     * Configurable so a shared cluster can prefix it per tenant. On a shared Elasticsearch the
     * app's role only grants access to its own prefix, so a hardcoded name would either be
     * unreachable or force every tenant to share one index.
     */
    private final String index;

    WaitingIndex(ElasticsearchClient elasticsearch,
                 @org.springframework.beans.factory.annotation.Value("${shush.search.waiting-index}") String index) {
        this.elasticsearch = elasticsearch;
        this.index = index;
    }

    public void index(WaitingUser waiting) throws IOException {
        ensureIndexExists();
        elasticsearch.index(request -> request
                .index(index)
                .id(waiting.userId().toString())
                // Visible to the very next search rather than after the default one-second
                // refresh: two people arriving together must be able to find each other, and a
                // second of invisibility is an eternity inside a five-second patience window.
                .refresh(co.elastic.clients.elasticsearch._types.Refresh.True)
                .document(new WaitingDocument(
                        waiting.userId().toString(),
                        waiting.interestIds().stream().map(String::valueOf).toList(),
                        waiting.enqueuedAt().toEpochMilli())));
    }

    public void remove(UUID userId) {
        try {
            elasticsearch.delete(request -> request
                    .index(index)
                    .id(userId.toString())
                    .refresh(co.elastic.clients.elasticsearch._types.Refresh.True));
        } catch (IOException | RuntimeException e) {
            // The wait pool is the authority on who is waiting; a stale document here only
            // costs one wasted claim attempt, which the atomic claim already handles.
            log.debug("could not remove {} from the waiting index", userId);
        }
    }

    /**
     * @return the best-overlapping waiting user, or empty if nobody eligible is waiting
     */
    public Optional<Candidate> bestMatchFor(WaitingUser searcher, Collection<UUID> excluded)
            throws IOException {
        ensureIndexExists();

        List<String> wantedTags = searcher.interestIds().stream().map(String::valueOf).toList();
        List<String> excludedIds = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(searcher.userId()), excluded.stream())
                .map(UUID::toString)
                .toList();

        SearchResponse<WaitingDocument> response = elasticsearch.search(request -> request
                .index(index)
                .size(1)
                .query(query -> query.bool(bool -> bool
                        // Scored, not filtered: more shared tags is a better match, and that
                        // ranking is the whole reason for using a search engine here.
                        .must(must -> must.terms(terms -> terms
                                .field("interestIds")
                                .terms(values -> values.value(wantedTags.stream()
                                        .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                        .toList()))))
                        .mustNot(not -> not.terms(terms -> terms
                                .field("userId")
                                .terms(values -> values.value(excludedIds.stream()
                                        .map(co.elastic.clients.elasticsearch._types.FieldValue::of)
                                        .toList()))))))
                // Ties broken by who has been waiting longest, so nobody is starved by a
                // steady trickle of equally-good newcomers.
                .sort(sort -> sort.field(field -> field.field("_score")
                        .order(co.elastic.clients.elasticsearch._types.SortOrder.Desc)))
                .sort(sort -> sort.field(field -> field.field("enqueuedAt")
                        .order(co.elastic.clients.elasticsearch._types.SortOrder.Asc))),
                WaitingDocument.class);

        return response.hits().hits().stream()
                .findFirst()
                .map(Hit::source)
                .filter(java.util.Objects::nonNull)
                .map(document -> new Candidate(
                        UUID.fromString(document.userId()),
                        document.interestIds().stream().map(Short::valueOf).toList(),
                        Instant.ofEpochMilli(document.enqueuedAt())));
    }

    /**
     * Created on first use rather than at startup, so the application still boots and serves
     * chat when search is unavailable. Matching is the only thing that needs it.
     */
    private void ensureIndexExists() throws IOException {
        if (elasticsearch.indices().exists(request -> request.index(index)).value()) {
            return;
        }
        try {
            elasticsearch.indices().create(request -> request
                    .index(index)
                    .mappings(mappings -> mappings
                            .properties("userId", property -> property.keyword(keyword -> keyword))
                            .properties("interestIds", property -> property.keyword(keyword -> keyword))
                            .properties("enqueuedAt", property -> property.long_(number -> number)))
                    // One shard, no replica: a single node has nowhere to put a replica, and an
                    // unassigned one would leave the cluster permanently yellow for no benefit.
                    .settings(settings -> settings.numberOfShards("1").numberOfReplicas("0")));
        } catch (RuntimeException alreadyCreatedConcurrently) {
            log.debug("the waiting index already exists");
        }
    }

    public record Candidate(UUID userId, List<Short> interestIds, Instant enqueuedAt) {}

    record WaitingDocument(String userId, List<String> interestIds, long enqueuedAt) {}
}
