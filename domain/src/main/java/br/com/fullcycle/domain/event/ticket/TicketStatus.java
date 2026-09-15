package br.com.fullcycle.domain.event.ticket;

public enum TicketStatus {
    PENDING, PROCESSING, PAID, CANCELLED;

    public Boolean isCancelled() {
        return this == CANCELLED;
    }
}
