package br.com.fullcycle.domain.event.ticket;

import br.com.fullcycle.domain.customer.CustomerId;
import br.com.fullcycle.domain.event.EventId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class TicketCancellationTest {
    @ParameterizedTest
    @EnumSource(TicketStatus.class)
    void givenAnyStatusWhenCancelledTwiceThenPreserveIdentityAndDates(TicketStatus status) {
        var reservedAt = Instant.now();
        var paidAt = status == TicketStatus.PAID ? reservedAt : null;
        var ticket = new Ticket(TicketId.unique(), CustomerId.unique(), EventId.unique(), status, paidAt, reservedAt);
        var id = ticket.ticketId();
        ticket.cancel();
        assertDoesNotThrow(ticket::cancel);
        assertEquals(TicketStatus.CANCELLED, ticket.status());
        assertEquals(id, ticket.ticketId());
        assertEquals(reservedAt, ticket.reservedAt());
        assertEquals(paidAt, ticket.paidAt());
    }
}
