package site.syamdev.shush.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.syamdev.shush.common.ApiException;
import site.syamdev.shush.config.StorageProperties;
import site.syamdev.shush.conversation.ConversationService;
import site.syamdev.shush.user.User;
import site.syamdev.shush.user.UserRepository;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Media never passes through the API.
 *
 * <p>The client asks for a short-lived URL scoped to exactly one key, uploads the bytes straight
 * to storage, and then sends a message referencing the key. The service handles kilobytes of
 * metadata instead of megabytes of image, which is why five-megabyte uploads do not compete with
 * message delivery for heap, bandwidth or request threads -- and why nginx's body-size limit is
 * irrelevant here.
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);

    /**
     * An allowlist, never a denylist. Anything not named here cannot be uploaded at all, which
     * is the only version of this check that is safe to be wrong about.
     */
    private static final Set<String> ALLOWED_MIME_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    private final MediaObjectRepository media;
    private final ConversationService conversations;
    private final UserRepository users;
    private final S3Client s3;
    private final S3Presigner presigner;
    private final StorageProperties properties;
    private final Clock clock;
    private volatile boolean bucketReady;

    MediaService(MediaObjectRepository media, ConversationService conversations,
                 UserRepository users, S3Client s3, S3Presigner presigner,
                 StorageProperties properties, Clock clock) {
        this.media = media;
        this.conversations = conversations;
        this.users = users;
        this.s3 = s3;
        this.presigner = presigner;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Upload issueUploadUrl(UUID conversationId, UUID uploaderId, String mime, long sizeBytes) {
        conversations.requireParticipant(conversationId, uploaderId);
        ensureBucketExists();

        if (mime == null || !ALLOWED_MIME_TYPES.contains(mime.toLowerCase())) {
            throw ApiException.badRequest("unsupported_type",
                    "images only: " + String.join(", ", ALLOWED_MIME_TYPES));
        }
        if (sizeBytes <= 0 || sizeBytes > properties.maxBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "too_large",
                    "an image is at most " + properties.maxBytes() + " bytes");
        }

        Instant now = clock.instant();
        // Namespaced by conversation, so a key reveals nothing about who uploaded it and the
        // objects for a purged conversation are trivially identifiable.
        String key = "media/" + conversationId + "/" + UUID.randomUUID();

        media.save(new MediaObject(key, uploaderId, conversationId, mime.toLowerCase(), now,
                now.plus(retentionFor(conversationId))));

        String url = presigner.presignPutObject(PutObjectPresignRequest.builder()
                        .signatureDuration(properties.uploadUrlTtl())
                        .putObjectRequest(PutObjectRequest.builder()
                                .bucket(properties.bucket())
                                .key(key)
                                .contentType(mime.toLowerCase())
                                .build())
                        .build())
                .url().toString();

        return new Upload(key, url, now.plus(properties.uploadUrlTtl()));
    }

    /**
     * Confirms the bytes are really there before the message is accepted.
     *
     * <p>Without this a client could reference a key it never uploaded, and every recipient would
     * get a message pointing at nothing. Storage is asked, not the client -- including for the
     * size, because the number in the original request was only ever a claim.
     *
     * @return true if the object exists and the message may be accepted
     */
    @Transactional
    public boolean confirm(String key, UUID conversationId, UUID senderId) {
        MediaObject object = media.findById(key).orElse(null);
        if (object == null
                || !object.getConversationId().equals(conversationId)
                || !object.getUploaderId().equals(senderId)) {
            // Also covers someone else's key: a media reference is only valid for the person
            // who was issued it, in the conversation it was issued for.
            return false;
        }
        if (object.getStatus() == MediaObject.Status.CONFIRMED) {
            return true;
        }

        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket())
                    .key(key)
                    .build());
            object.confirm(head.contentLength());
            return true;
        } catch (NoSuchKeyException absent) {
            return false;
        } catch (S3Exception e) {
            log.warn("could not confirm media object; treating it as absent");
            return false;
        }
    }

    /**
     * Images in a conversation where neither person has saved their account are kept for a day;
     * with an account, thirty. Storing images costs real money, so this reflects a genuine
     * difference rather than an invented restriction (pre-plan.md 5, point 4).
     */
    private java.time.Duration retentionFor(UUID conversationId) {
        List<UUID> participantIds = conversations.participantIds(conversationId);
        boolean anyoneSaved = users.findAllById(participantIds).stream()
                .anyMatch(user -> !user.isAnonymous());
        return anyoneSaved ? properties.savedRetention() : properties.anonymousRetention();
    }

    /**
     * A short-lived URL to read one object.
     *
     * <p>A redirect rather than a proxy, for the same reason uploads are presigned: the bytes
     * are the one thing that must not travel through the API. Serving them here would put every
     * image on the same threads and heap as message delivery.
     */
    @Transactional(readOnly = true)
    public String readUrl(String key, UUID viewerId) {
        MediaObject object = media.findById(key)
                .orElseThrow(() -> ApiException.notFound("unknown_media", "no such image"));
        // Membership, not possession of the key: a key is not a capability here.
        conversations.requireParticipant(object.getConversationId(), viewerId);

        return presigner.presignGetObject(GetObjectPresignRequest.builder()
                        .signatureDuration(properties.uploadUrlTtl())
                        .getObjectRequest(GetObjectRequest.builder()
                                .bucket(properties.bucket())
                                .key(key)
                                .build())
                        .build())
                .url().toString();
    }

    /**
     * @return true if the object is gone -- deleted now, or already absent. False means storage
     *         refused, and the row is kept so the next sweep tries again rather than leaving an
     *         object nothing references and nothing can find.
     */
    public boolean deleteObject(String key) {
        try {
            s3.deleteObject(request -> request.bucket(properties.bucket()).key(key));
            return true;
        } catch (NoSuchKeyException alreadyGone) {
            return true;
        } catch (SdkException e) {
            log.debug("could not delete {}; the retention sweep will try again", key);
            return false;
        }
    }

    /**
     * Created on first use rather than at startup, so the application still boots and serves
     * chat when object storage is unavailable -- only images stop working.
     */
    private void ensureBucketExists() {
        if (bucketReady) {
            return;
        }
        try {
            s3.headBucket(request -> request.bucket(properties.bucket()));
        } catch (S3Exception absent) {
            // MinIO answers a missing bucket with a plain 404 rather than a typed
            // NoSuchBucketException, so this catches the general case deliberately.
            try {
                s3.createBucket(request -> request.bucket(properties.bucket()));
            } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException raced) {
                log.debug("the media bucket already exists");
            }
        }
        bucketReady = true;
    }

    public record Upload(String key, String uploadUrl, Instant urlExpiresAt) {}
}
