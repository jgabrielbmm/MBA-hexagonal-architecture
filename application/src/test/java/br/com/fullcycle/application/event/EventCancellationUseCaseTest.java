package br.com.fullcycle.application.event;

import br.com.fullcycle.application.Presenter;
import br.com.fullcycle.application.repository.InMemoryEventRepository;
import br.com.fullcycle.application.repository.InMemoryCustomerRepository;
import br.com.fullcycle.domain.customer.Customer;
import br.com.fullcycle.domain.event.*;
import br.com.fullcycle.domain.exceptions.ValidationException;
import br.com.fullcycle.domain.partner.Partner;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class EventCancellationUseCaseTest {
    private final InMemoryEventRepository events = new InMemoryEventRepository();
    private final CancelEventUseCase cancel = new CancelEventUseCase(events);
    private final GetEventByIdUseCase get = new GetEventByIdUseCase(events);

    private Event newEvent() {
        return events.create(Event.newEvent("Disney on Ice", "2021-01-01", 100,
                Partner.newPartner("Disney", "45.123.123/0001-12", "disney@gmail.com")));
    }

    @Test
    void givenActiveEventWhenCancelledThenPersistStatusAndReturnCommandOutput() {
        var event = newEvent();
        var output = cancel.execute(new CancelEventUseCase.Input(event.eventId().value()));
        assertEquals(event.eventId().value(), output.id());
        assertEquals("CANCELLED", output.status());
        var saved = events.eventOfId(event.eventId()).orElseThrow();
        assertEquals(EventStatus.CANCELLED, saved.status());
        assertTrue(saved.allDomainEvents().stream().anyMatch(EventCancelled.class::isInstance));
    }

    @Test
    void givenMissingEventWhenCancelledThenReject() {
        var error = assertThrows(ValidationException.class,
                () -> cancel.execute(new CancelEventUseCase.Input(EventId.unique().value())));
        assertEquals("Event not found", error.getMessage());
    }

    @Test
    void givenCancelledEventWhenCancelledAgainThenReject() {
        var event = newEvent();
        var input = new CancelEventUseCase.Input(event.eventId().value());
        cancel.execute(input);
        assertEquals("Event already cancelled", assertThrows(ValidationException.class,
                () -> cancel.execute(input)).getMessage());
    }

    @Test
    void givenCancelledEventWhenCustomerSubscribesThenReject() {
        var customers = new InMemoryCustomerRepository();
        var customer = customers.create(Customer.newCustomer("John Doe", "123.456.789-00", "john@gmail.com"));
        var event = newEvent();
        cancel.execute(new CancelEventUseCase.Input(event.eventId().value()));
        var subscribe = new SubscribeCustomerToEventUseCase(customers, events);
        var error = assertThrows(ValidationException.class, () -> subscribe.execute(
                new SubscribeCustomerToEventUseCase.Input(customer.customerId().value(), event.eventId().value())));
        assertEquals("Cannot reserve a ticket for a canceled event", error.getMessage());
        assertTrue(events.eventOfId(event.eventId()).orElseThrow().allTickets().isEmpty());
    }

    @Test
    void givenEventWhenQueriedThenReturnFieldsAndCurrentStatusThroughPresenter() {
        var event = newEvent();
        var input = new GetEventByIdUseCase.Input(event.eventId().value());
        assertEquals(new GetEventByIdUseCase.Output(event.eventId().value(), "Disney on Ice", "2021-01-01", 100, "ACTIVE"),
                get.execute(input).orElseThrow());
        cancel.execute(new CancelEventUseCase.Input(event.eventId().value()));
        Presenter<Optional<GetEventByIdUseCase.Output>, String> presenter = new Presenter<>() {
            public String present(Optional<GetEventByIdUseCase.Output> output) {
                return output.map(GetEventByIdUseCase.Output::status).orElse("missing");
            }
            public String present(Throwable error) { throw new AssertionError(error); }
        };
        assertEquals("CANCELLED", get.execute(input, presenter));
        assertEquals("missing", get.execute(new GetEventByIdUseCase.Input(EventId.unique().value()), presenter));
    }
}
