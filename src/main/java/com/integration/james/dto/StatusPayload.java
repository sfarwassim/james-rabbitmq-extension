package com.integration.james.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Objects;

public class StatusPayload {

    private final String hashID;
    private final String status; // "success" or "failed"

    @JsonCreator
    public StatusPayload(@JsonProperty("hashID") String hashID,
                         @JsonProperty("status") String status) {
        this.hashID = hashID;
        this.status = status;
    }

    public String getHashID() {
        return hashID;
    }

    public String getStatus() {
        return status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        StatusPayload that = (StatusPayload) o;
        return Objects.equals(hashID, that.hashID) &&
                Objects.equals(status, that.status);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hashID, status);
    }

    @Override
    public String toString() {
        return "StatusPayload{" +
                "hashID='" + hashID + '\'' +
                ", status='" + status + '\'' +
                '}';
    }
}