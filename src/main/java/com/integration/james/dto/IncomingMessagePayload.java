package com.integration.james.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public class IncomingMessagePayload {

    private final String action;
    private final String sourceMailboxID;
    private final String sourceMessageID; // Expected to be a message UID
    private final String destinationMailboxID; // nullable for TRASH
    private final String hashID;

    @JsonCreator
    public IncomingMessagePayload(@JsonProperty("action") String action,
                                  @JsonProperty("sourceMailboxID") String sourceMailboxID,
                                  @JsonProperty("sourceMessageID") String sourceMessageID,
                                  @JsonProperty("destinationMailboxID") String destinationMailboxID,
                                  @JsonProperty("hashID") String hashID) {
        this.action = action;
        this.sourceMailboxID = sourceMailboxID;
        this.sourceMessageID = sourceMessageID;
        this.destinationMailboxID = destinationMailboxID;
        this.hashID = hashID;
    }

    public String getAction() {
        return action;
    }

    public String getSourceMailboxID() {
        return sourceMailboxID;
    }

    public String getSourceMessageID() {
        return sourceMessageID;
    }

    public String getDestinationMailboxID() {
        return destinationMailboxID;
    }

    public String getHashID() {
        return hashID;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        IncomingMessagePayload that = (IncomingMessagePayload) o;
        return Objects.equals(action, that.action) &&
                Objects.equals(sourceMailboxID, that.sourceMailboxID) &&
                Objects.equals(sourceMessageID, that.sourceMessageID) &&
                Objects.equals(destinationMailboxID, that.destinationMailboxID) &&
                Objects.equals(hashID, that.hashID);
    }

    @Override
    public int hashCode() {
        return Objects.hash(action, sourceMailboxID, sourceMessageID, destinationMailboxID, hashID);
    }

    @Override
    public String toString() {
        return "IncomingMessagePayload{" +
                "action='" + action + '\'' +
                ", sourceMailboxID='" + sourceMailboxID + '\'' +
                ", sourceMessageID='" + sourceMessageID + '\'' +
                ", destinationMailboxID='" + destinationMailboxID + '\'' +
                ", hashID='" + hashID + '\'' +
                '}';
    }
}