package br.com.fullcycle.application.ticket;

import br.com.fullcycle.application.repository.InMemoryTicketRepository;
import br.com.fullcycle.domain.customer.CustomerId;
import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.event.ticket.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CancelEventTicketsUseCaseTest {
    private final InMemoryTicketRepository tickets = new InMemoryTicketRepository();
    private final CancelEventTicketsUseCase useCase = new CancelEventTicketsUseCase(tickets);

    @Test
    void givenTicketsFromTwoEventsWhenCancellationReprocessedThenOnlyTargetTicketsAreCancelled() {
        var eventId = EventId.unique();
        var first = tickets.create(Ticket.newTicket(CustomerId.unique(), eventId));
        var second = tickets.create(Ticket.newTicket(CustomerId.unique(), eventId));
        var other = tickets.create(Ticket.newTicket(CustomerId.unique(), EventId.unique()));
        var input = new CancelEventTicketsUseCase.Input(eventId.value());
        assertEquals(2, useCase.execute(input).cancelledTickets());
        assertEquals(2, useCase.execute(input).cancelledTickets());
        assertEquals(TicketStatus.CANCELLED, tickets.ticketOfId(first.ticketId()).orElseThrow().status());
        assertEquals(TicketStatus.CANCELLED, tickets.ticketOfId(second.ticketId()).orElseThrow().status());
        assertEquals(TicketStatus.PENDING, tickets.ticketOfId(other.ticketId()).orElseThrow().status());
    }

    @Test
    void givenNoTicketsWhenCancelledThenSucceed() {
        assertEquals(0, useCase.execute(new CancelEventTicketsUseCase.Input(EventId.unique().value())).cancelledTickets());
    }
}
