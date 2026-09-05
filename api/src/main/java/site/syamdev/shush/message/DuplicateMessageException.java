package site.syamdev.shush.message;

/**
 * Raised when the dedup constraint rejected an insert. It escapes the writing transaction on
 * purpose: rolling back is what releases the sequence number that was claimed for a message
 * that turned out to already exist.
 */
class DuplicateMessageException extends RuntimeException {

    DuplicateMessageException() {
        super("duplicate clientMsgId", null, false, false);
    }
}
