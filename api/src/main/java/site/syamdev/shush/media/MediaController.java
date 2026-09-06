package site.syamdev.shush.media;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import site.syamdev.shush.common.CurrentUser;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/media")
class MediaController {

    private final MediaService media;
    private final CurrentUser currentUser;

    MediaController(MediaService media, CurrentUser currentUser) {
        this.media = media;
        this.currentUser = currentUser;
    }

    /** Returns a URL, never accepts bytes. The API never sees the image at all. */
    @PostMapping("/upload-url")
    UploadResponse uploadUrl(@Valid @RequestBody UploadRequest request) {
        MediaService.Upload upload = media.issueUploadUrl(request.conversationId(),
                currentUser.requireId(), request.mime(), request.sizeBytes());
        return new UploadResponse(upload.key(), upload.uploadUrl(), upload.urlExpiresAt());
    }

    /**
     * Redirects to a short-lived read URL. The API answers with a location, never with bytes.
     * The trailing wildcard is because a key contains slashes.
     */
    @GetMapping("/**")
    ResponseEntity<Void> read(HttpServletRequest request) {
        String key = request.getRequestURI().substring("/api/media/".length());
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(media.readUrl(URLDecoder.decode(key, StandardCharsets.UTF_8),
                        currentUser.requireId())))
                .build();
    }

    record UploadRequest(@NotNull UUID conversationId, @NotBlank String mime,
                         @Positive long sizeBytes) {
    }

    record UploadResponse(String key, String uploadUrl, Instant urlExpiresAt) {}
}
