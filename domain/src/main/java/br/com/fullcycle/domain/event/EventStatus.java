package br.com.fullcycle.domain.event;

public enum EventStatus {
    ACTIVE,
    CANCELLED;

    public Boolean isCancelled() {
        return this == CANCELLED;
    }
}
