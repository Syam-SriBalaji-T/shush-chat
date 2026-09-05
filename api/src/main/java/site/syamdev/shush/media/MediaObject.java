package site.syamdev.shush.media;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A row per object, written before the bytes exist.
 *
 * <p>The row is created {@code pending} when the upload URL is issued and only becomes
 * {@code confirmed} once the object has actually been seen in storage. Without the pending row
 * there would be no record that a key was ever handed out, and an upload the client abandoned
 * would sit in the bucket forever with nothing referencing it and nothing to find it by.
 */
@Entity
@Table(name = "media_objects")
public class MediaObject {

    @Id
    @Column(name = "key", columnDefinition = "text")
    private String key;

    @Column(name = "uploader_id", nullable = false)
    private UUID uploaderId;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "status", nullable = false, columnDefinition = "text")
    private String status;

    @Column(name = "mime", nullable = false, columnDefinition = "text")
    private String mime;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected MediaObject() {
    }

    public MediaObject(String key, UUID uploaderId, UUID conversationId, String mime,
                       Instant createdAt, Instant expiresAt) {
        this.key = key;
        this.uploaderId = uploaderId;
        this.conversationId = conversationId;
        this.status = Status.PENDING.wireValue();
        this.mime = mime;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public String getKey() {
        return key;
    }

    public UUID getUploaderId() {
        return uploaderId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public Status getStatus() {
        return Status.fromWire(status);
    }

    public String getMime() {
        return mime;
    }

    public Long getSizeBytes() {
        return sizeBytes;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    /** @param actualSizeBytes what storage reports, not what the client claimed */
    void confirm(long actualSizeBytes) {
        this.status = Status.CONFIRMED.wireValue();
        this.sizeBytes = actualSizeBytes;
    }

    public enum Status {
        PENDING, CONFIRMED;

        public String wireValue() {
            return name().toLowerCase();
        }

        public static Status fromWire(String value) {
            return valueOf(value.toUpperCase());
        }
    }
}
