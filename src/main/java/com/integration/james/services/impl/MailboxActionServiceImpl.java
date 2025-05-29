package com.integration.james.services.impl;

import com.integration.james.dto.IncomingMessagePayload;
import com.integration.james.services.MailboxActionService;
import org.apache.james.core.Username;
import org.apache.james.mailbox.MailboxManager;
import org.apache.james.mailbox.MailboxSession;
import org.apache.james.mailbox.MessageManager;
import org.apache.james.mailbox.MessageUid;
import org.apache.james.mailbox.exception.MailboxException;
import org.apache.james.mailbox.exception.MailboxNotFoundException;
import org.apache.james.mailbox.model.*;


import org.apache.james.mailbox.store.MailboxSessionMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import java.util.List;
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
    private final MailboxSessionMapperFactory mapperFactory;


    @Inject
    public MailboxActionServiceImpl(MailboxManager mailboxManager, MailboxId.Factory mailboxIdFactory, MailboxSessionMapperFactory mapperFactory)
    {
        this.mailboxManager = mailboxManager;
        this.mailboxIdFactory = mailboxIdFactory;
        this.mapperFactory = mapperFactory;
        LOGGER.info("Constructor MailboxActionServiceImpl...");

    }

    @Override
    public boolean processMessageAction(IncomingMessagePayload payload) throws MailboxException {
        MailboxSession operationSession  = null;
        try {

            MailboxId srcId  = mailboxIdFactory.fromString(payload.getSourceMailboxID());
            MessageUid uid   = MessageUid.of(Long.parseLong(payload.getSourceMessageID()));
            MailboxPath srcPath;
            Username owner;

            MailboxSession adminLookupSession = null;

            try {
                 Username adminUser = Username.of(ADMIN_USERNAME_FOR_SESSION);
                 adminLookupSession = mailboxManager.createSystemSession(adminUser);
                 mailboxManager.startProcessingRequest(adminLookupSession);

                Mailbox mailbox = mapperFactory.getMailboxMapper(adminLookupSession)
                        .findMailboxById(srcId)
                        .switchIfEmpty(Mono.error(new MailboxNotFoundException(srcId)))
                        .block();
                if (mailbox == null) {
                    throw new MailboxNotFoundException("Source Mailbox ID " + srcId + " resolved to null");
                }
                srcPath = mailbox.generateAssociatedPath();
                owner   = mailbox.getUser();

            } finally {
                if (adminLookupSession != null) {
                    mailboxManager.endProcessingRequest(adminLookupSession);
                }
            }

            operationSession = mailboxManager.createSystemSession(owner);
            mailboxManager.startProcessingRequest(operationSession);



            switch (payload.getAction()) {
                case TRASH_ACTION -> {
                    return trashMessage(operationSession, owner, srcPath, uid, payload.getHashID());
                }
                case MOVE_ACTION -> {
                    if (payload.getDestinationMailboxID() == null || payload.getDestinationMailboxID().isBlank()) {
                        LOGGER.error("Missing destinationMailboxID for MOVE – hashID={}", payload.getHashID());
                        return false;
                    }
                    MailboxId destId = mailboxIdFactory.fromString(payload.getDestinationMailboxID());
                    Mailbox destMailBox = mapperFactory.getMailboxMapper(operationSession)
                            .findMailboxById(destId)
                            .switchIfEmpty(Mono.error(new MailboxNotFoundException(destId)))
                            .block();
                    if (destMailBox == null) {
                        throw new MailboxNotFoundException("Destination Mailbox ID " + destId + " resolved to null");
                    }
                    MailboxPath destPath = destMailBox.generateAssociatedPath();
                    if(!destMailBox.getUser().equals(owner)) {

                        LOGGER.warn("Cross-user mailbox move initiated by source owner {}. " +
                                        "Source Mailbox: {} (Owner: {}), Destination Mailbox: {} (Owner: {}). hashID={}",
                                owner, srcPath.getName(), owner, destPath.getName(), destMailBox.getUser(), payload.getHashID());
                    }

                    return moveMessage(operationSession, srcPath, destPath, uid, payload.getHashID());
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
            if (operationSession != null) {
                mailboxManager.endProcessingRequest(operationSession);
            }
        }
    }

// ---------- helpers ----------------------------------------------------

    private boolean trashMessage(MailboxSession session,
                                 Username owner,
                                 MailboxPath srcPath,
                                 MessageUid uid,
                                 String hashID) throws MailboxException {

        MailboxPath trashPath = MailboxPath.forUser(owner, DEFAULT_TRASH_FOLDER_NAME);

        // Create Trash if it doesn’t exist
        Mono<Boolean> exists = Mono.from(mailboxManager.mailboxExists(trashPath, session));
        if (Boolean.FALSE.equals(exists.block())) {
            Optional<MailboxId> created = mailboxManager.createMailbox(trashPath, MailboxManager.CreateOption.NONE, session);
            if (created.isEmpty()) {
                LOGGER.error("TRASH action (hashID: {}): Failed to create Trash mailbox {} for user {}. UID {} not trashed.", hashID, trashPath, owner, uid);
                return false;
            }
            LOGGER.info("TRASH action (hashID: {}): Created Trash mailbox {} for user {}.", hashID, trashPath, owner);
        }

        List<MessageRange> moved = mailboxManager.moveMessages(MessageRange.one(uid), srcPath, trashPath, session);

        boolean success = !moved.isEmpty();
        if (success) {

            LOGGER.info("TRASH action (hashID: {}): Successfully moved UID {} to Trash for user {}.", hashID, uid, owner);
            return true;
        }
        LOGGER.warn("UID {} not found in {} – nothing trashed", uid, srcPath);

        return false;
    }

    private boolean moveMessage(MailboxSession session,
                                MailboxPath srcPath,
                                MailboxPath destPath,
                                MessageUid uid,
                                String hashID) throws MailboxException {
        MessageManager srcMM = mailboxManager.getMailbox(srcPath, session);

        List<MessageRange> moved = mailboxManager.moveMessages(MessageRange.one(uid), srcPath, destPath, session);

        boolean success = !moved.isEmpty();
        if (success) {
            LOGGER.info("MOVE action (hashID: {}): Successfully moved UID {} from {} to {}.", hashID, uid, srcPath, destPath);
            return true;
        }
        LOGGER.warn("UID {} not found in {} – nothing moved to {}", uid, srcPath, destPath);

        return false;
    }
}