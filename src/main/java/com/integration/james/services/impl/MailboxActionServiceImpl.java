package com.integration.james.services.impl;

import com.integration.james.dto.IncomingMessagePayload;
import com.integration.james.services.MailboxActionService;
import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageManager;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.model.MailboxPath;
import org.apache.james.mailbox.model.MessageRange;
import org.apache.james.mailbox.model.MailboxId;



import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.Optional;

import reactor.core.publisher.Mono;

@Singleton
public class MailboxActionServiceImpl implements MailboxActionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MailboxActionServiceImpl.class);
    private static final String TRASH_ACTION = "Trash";
    private static final String MOVE_ACTION = "Move";
    private static final String DEFAULT_TRASH_FOLDER_NAME = "Trash";

    // This should be configurable or confirmed with the client
    private static final String ADMIN_USERNAME_FOR_SESSION = "admin@james.local"; // IMPORTANT: Placeholder!


    private final MailboxManager mailboxManager;
    private final MailboxId.Factory mailboxIdFactory;

    @Inject
    public MailboxActionServiceImpl(MailboxManager mailboxManager, MailboxId.Factory mailboxIdFactory)
    {
        this.mailboxManager = mailboxManager;
        this.mailboxIdFactory = mailboxIdFactory;
        LOGGER.info("Constructor MailboxActionServiceImpl...");

    }

    @Override
    public boolean processMessageAction(IncomingMessagePayload payload) throws MailboxException {
        MailboxSession session = null;
        try {
            // 1. System session – does NOT need to be the owner of the mailbox

            // IMPORTANT: Using a placeholder admin user. This user MUST have permissions
            // to access arbitrary mailboxes by ID. This should be configured.
            Username sessionUser = Username.of(ADMIN_USERNAME_FOR_SESSION);
            session = mailboxManager.createSystemSession(sessionUser);
            mailboxManager.startProcessingRequest(session);

            // 2. Parse IDs coming from RabbitMQ
            MailboxId srcId  = mailboxIdFactory.fromString(payload.getSourceMailboxID());
            MessageUid uid   = MessageUid.of(Long.parseLong(payload.getSourceMessageID()));

            MessageManager srcMM  = mailboxManager.getMailbox(srcId, session);
            MailboxPath    srcPath = srcMM.getMailboxPath();
            Username       owner   = srcPath.getUser(); // needed for Trash target

            switch (payload.getAction()) {
                case TRASH_ACTION -> {
                    return trashMessage(session, owner, srcPath, uid);
                }
                case MOVE_ACTION -> {
                    if (payload.getDestinationMailboxID() == null || payload.getDestinationMailboxID().isBlank()) {
                        LOGGER.error("Missing destinationMailboxID for MOVE – hashID={}", payload.getHashID());
                        return false;
                    }
                    MailboxId destId = mailboxIdFactory.fromString(payload.getDestinationMailboxID());
                    MessageManager destMM = mailboxManager.getMailbox(destId, session);
                    return moveMessage(session, srcPath, destMM.getMailboxPath(), uid);
                }
                default -> {
                    LOGGER.warn("Unknown action '{}' – hashID={}", payload.getAction(), payload.getHashID());
                    return false;
                }
            }

        } catch (NumberFormatException nfe) {
            LOGGER.error("sourceMessageID is not a valid UID: '{}' – hashID={}", payload.getSourceMessageID(), payload.getHashID(), nfe);
            return false;
        } catch (MailboxException me) {
            LOGGER.error("Mailbox error while processing hashID={}: {}", payload.getHashID(), me.getMessage(), me);
            return false;
        } catch (Exception e) {
            LOGGER.error("Unexpected error while processing hashID={}: {}", payload.getHashID(), e.getMessage(), e);
            return false;
        } finally {
            if (session != null) {
                mailboxManager.endProcessingRequest(session);
            }
        }
    }

// ---------- helpers ----------------------------------------------------

    private boolean trashMessage(MailboxSession session,
                                 Username owner,
                                 MailboxPath srcPath,
                                 MessageUid uid) throws MailboxException {

        MailboxPath trashPath = MailboxPath.forUser(owner, DEFAULT_TRASH_FOLDER_NAME);

        // Create Trash if it doesn’t exist
        Mono<Boolean> exists = Mono.from(mailboxManager.mailboxExists(trashPath, session));
        if (Boolean.FALSE.equals(exists.block())) {
            Optional<MailboxId> created = mailboxManager.createMailbox(trashPath, MailboxManager.CreateOption.NONE, session);
            if (created.isEmpty()) {
                LOGGER.error("Failed to create Trash mailbox {} for user {}", trashPath, owner);
                return false;
            }
            LOGGER.info("Created Trash mailbox {} for user {}", trashPath, owner);
        }

        LOGGER.info("Moving UID {} from {} → Trash ({})", uid, srcPath, trashPath);
        mailboxManager.moveMessages(MessageRange.one(uid), srcPath, trashPath, session);
        return true;
    }

    private boolean moveMessage(MailboxSession session,
                                MailboxPath srcPath,
                                MailboxPath destPath,
                                MessageUid uid) throws MailboxException {
        LOGGER.info("Moving UID {} from {} → {}", uid, srcPath, destPath);
        mailboxManager.moveMessages(MessageRange.one(uid), srcPath, destPath, session);
        return true;
    }
}