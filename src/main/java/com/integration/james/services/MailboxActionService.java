package com.integration.james.services;
import com.integration.james.dto.IncomingMessagePayload;
import org.apache.james.mailbox.exception.MailboxException;

public interface MailboxActionService {

    boolean processMessageAction(IncomingMessagePayload payload) throws MailboxException;

}
