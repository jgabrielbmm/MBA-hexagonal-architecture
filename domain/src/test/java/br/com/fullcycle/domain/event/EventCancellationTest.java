package br.com.fullcycle.domain.event;

import br.com.fullcycle.domain.customer.CustomerId;
import br.com.fullcycle.domain.exceptions.ValidationException;
import br.com.fullcycle.domain.partner.Partner;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventCancellationTest {
    private Event newEvent() {
        return Event.newEvent("Disney on Ice", "2021-01-01", 100,
                Partner.newPartner("Disney", "45.123.123/0001-12", "disney@gmail.com"));
    }

    @Test
    void givenActiveEventWhenCancelledThenRegisterDomainEvent() {
        var event = newEvent();
        assertEquals(EventStatus.ACTIVE, event.status());
        event.cancel();
        assertEquals(EventStatus.CANCELLED, event.status());
        assertEquals(1, event.allDomainEvents().size());
        var notification = assertInstanceOf(EventCancelled.class, event.allDomainEvents().iterator().next());
        assertEquals(event.eventId().value(), notification.eventId());
        assertEquals("event.cancelled", notification.type());
        assertNotNull(notification.domainEventId());
        assertNotNull(notification.occurredOn());
    }

    @Test
    void givenCancelledEventWhenCancelledAgainThenRejectWithoutAnotherEvent() {
        var event = newEvent();
        event.cancel();
        var notifications = event.allDomainEvents().size();
        var error = assertThrows(ValidationException.class, event::cancel);
        assertEquals("Event already cancelled", error.getMessage());
        assertEquals(notifications, event.allDomainEvents().size());
    }

    @Test
    void givenCancelledEventWhenReservingThenRejectWithoutReservation() {
        var event = newEvent();
        event.cancel();
        var error = assertThrows(ValidationException.class, () -> event.reserveTicket(CustomerId.unique()));
        assertEquals("Cannot reserve a ticket for a canceled event", error.getMessage());
        assertTrue(event.allTickets().isEmpty());
        assertEquals(1, event.allDomainEvents().size());
    }
}
