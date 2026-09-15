package br.com.fullcycle.infrastructure.event;

import br.com.fullcycle.IntegrationTest;
import br.com.fullcycle.application.event.CancelEventUseCase;
import br.com.fullcycle.domain.customer.Customer;
import br.com.fullcycle.domain.customer.CustomerRepository;
import br.com.fullcycle.domain.event.*;
import br.com.fullcycle.domain.event.ticket.*;
import br.com.fullcycle.domain.partner.Partner;
import br.com.fullcycle.domain.partner.PartnerRepository;
import br.com.fullcycle.infrastructure.gateways.QueueGateway;
import br.com.fullcycle.infrastructure.job.OutboxRelay;
import br.com.fullcycle.infrastructure.jpa.repositories.OutboxJpaRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:event_cancellation_test;MODE=MYSQL;DATABASE_TO_LOWER=TRUE")
class EventCancellationIntegrationTest extends IntegrationTest {
    // Control publication explicitly so the scheduled relay cannot mask routing failures.
    @MockBean private OutboxRelay relay;
    @Autowired private EventRepository events;
    @Autowired private TicketRepository tickets;
    @Autowired private PartnerRepository partners;
    @Autowired private CustomerRepository customers;
    @Autowired private OutboxJpaRepository outbox;
    @Autowired private CancelEventUseCase cancel;
    @Autowired private QueueGateway consumer;
    @Autowired private ObjectMapper mapper;
    @Autowired private MockMvc mvc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired @Qualifier("queueExecutor") private TaskExecutor queueExecutor;
    private Partner partner;
    private Customer customer;
    private Event event;

    @BeforeEach
    void setUp() {
        tickets.deleteAll();
        events.deleteAll();
        customers.deleteAll();
        partners.deleteAll();
        outbox.deleteAll();
        partner = partners.create(Partner.newPartner("Disney", "45.123.123/0001-12", "disney@gmail.com"));
        customer = customers.create(Customer.newCustomer("John Doe", "123.456.789-00", "john@gmail.com"));
        event = events.create(Event.newEvent("Disney on Ice", "2021-01-01", 100, partner));
    }

    @Test
    void givenPersistedTicketsWhenOutboxJsonPublishedThenCancelOnlyEventTicketsAndAllowReplay() throws Exception {
        var first = tickets.create(Ticket.newTicket(customer.customerId(), event.eventId()));
        var second = tickets.create(Ticket.newTicket(customer.customerId(), event.eventId()));
        var otherEvent = events.create(Event.newEvent("Other event", "2021-01-02", 10, partner));
        var other = tickets.create(Ticket.newTicket(customer.customerId(), otherEvent.eventId()));
        assertEquals(Set.of(first.ticketId(), second.ticketId()), tickets.ticketsByEventId(event.eventId())
                .stream().map(Ticket::ticketId).collect(Collectors.toSet()));
        assertTrue(tickets.ticketsByEventId(EventId.unique()).isEmpty());

        cancel.execute(new CancelEventUseCase.Input(event.eventId().value()));
        var restored = events.eventOfId(event.eventId()).orElseThrow();
        assertEquals(EventStatus.CANCELLED, restored.status());
        assertTrue(restored.allDomainEvents().isEmpty());
        assertEquals(TicketStatus.PENDING, tickets.ticketOfId(first.ticketId()).orElseThrow().status());
        assertEquals(TicketStatus.PENDING, tickets.ticketOfId(second.ticketId()).orElseThrow().status());

        var pending = new TransactionTemplate(transactionManager)
                .execute(transaction -> outbox.findTop100ByPublishedFalse());
        assertNotNull(pending);
        assertEquals(1, pending.size());
        var json = pending.get(0).content();
        var notification = mapper.readValue(json, EventCancelled.class);
        assertEquals("event.cancelled", notification.type());
        assertEquals(event.eventId().value(), notification.eventId());

        // Wait for each real asynchronous delivery before replaying the same outbox message.
        var executor = (ThreadPoolTaskExecutor) queueExecutor;
        for (int delivery = 0; delivery < 2; delivery++) {
            var completed = executor.getThreadPoolExecutor().getCompletedTaskCount();
            consumer.publish(json);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertTrue(executor.getThreadPoolExecutor().getCompletedTaskCount() > completed);
                assertEquals(TicketStatus.CANCELLED, tickets.ticketOfId(first.ticketId()).orElseThrow().status());
                assertEquals(TicketStatus.CANCELLED, tickets.ticketOfId(second.ticketId()).orElseThrow().status());
            });
        }
        assertEquals(TicketStatus.PENDING, tickets.ticketOfId(other.ticketId()).orElseThrow().status());
    }

    @Test
    void givenReservationWhenOutboxJsonPublishedThenOriginalTicketCreationStillWorks() {
        var reservation = event.reserveTicket(customer.customerId());
        events.update(event);
        var pending = new TransactionTemplate(transactionManager)
                .execute(transaction -> outbox.findTop100ByPublishedFalse());
        assertNotNull(pending);
        assertEquals(1, pending.size());
        consumer.publish(pending.get(0).content());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var created = tickets.ticketsByEventId(event.eventId());
            assertEquals(1, created.size());
            assertEquals(customer.customerId(), created.get(0).customerId());
            assertEquals(TicketStatus.PENDING, created.get(0).status());
        });
        var notifications = new TransactionTemplate(transactionManager)
                .execute(transaction -> outbox.findTop100ByPublishedFalse());
        assertNotNull(notifications);
        assertTrue(notifications.stream().anyMatch(it -> it.content().contains("ticket.created")
                && it.content().contains(reservation.eventTicketId().value())));
    }

    @Test
    void givenEventWhenUsingRestThenPresentBothRepresentationsAndCancellationErrors() throws Exception {
        var id = event.eventId().value();
        mvc.perform(get("/events/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.name").value("Disney on Ice"))
                .andExpect(jsonPath("$.date").value("2021-01-01"))
                .andExpect(jsonPath("$.totalSpots").value(100)).andExpect(jsonPath("$.status").value("ACTIVE"));
        mvc.perform(get("/events/{id}", id).header("X-Public", "true")).andExpect(status().isOk())
                .andExpect(content().json(mapper.writeValueAsString(Map.of("id", id, "status", "ACTIVE")), true));
        mvc.perform(post("/events/{id}/cancel", id)).andExpect(status().isOk())
                .andExpect(content().json(mapper.writeValueAsString(Map.of("id", id, "status", "CANCELLED")), true));
        mvc.perform(get("/events/{id}", id)).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(get("/events/{id}", id).header("X-Public", "true"))
                .andExpect(content().json(mapper.writeValueAsString(Map.of("id", id, "status", "CANCELLED")), true));
        mvc.perform(post("/events/{id}/cancel", id)).andExpect(status().isUnprocessableEntity())
                .andExpect(content().string("Event already cancelled"));
        mvc.perform(post("/events/{id}/subscribe", id).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("customerId", customer.customerId().value()))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().string("Cannot reserve a ticket for a canceled event"));
    }

    @Test
    void givenMissingEventWhenUsingRestThenReturnEmpty404OrCommand422() throws Exception {
        var id = EventId.unique().value();
        mvc.perform(get("/events/{id}", id)).andExpect(status().isNotFound()).andExpect(content().string(""));
        mvc.perform(get("/events/{id}", id).header("X-Public", "true"))
                .andExpect(status().isNotFound()).andExpect(content().string(""));
        mvc.perform(post("/events/{id}/cancel", id)).andExpect(status().isUnprocessableEntity())
                .andExpect(content().string("Event not found"));
    }

    @Test
    void givenEventWhenUsingGraphqlThenQueryAndCancelWithExistingCreationStillValid() throws Exception {
        var id = event.eventId().value();
        graphql("{ eventOfId(id: \"" + id + "\") { id name date totalSpots status } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.eventOfId.name").value("Disney on Ice"))
                .andExpect(jsonPath("$.data.eventOfId.id").value(id))
                .andExpect(jsonPath("$.data.eventOfId.date").value("2021-01-01"))
                .andExpect(jsonPath("$.data.eventOfId.totalSpots").value(100))
                .andExpect(jsonPath("$.data.eventOfId.status").value("ACTIVE"));
        graphql("mutation { cancelEvent(id: \"" + id + "\") { id status } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.cancelEvent.id").value(id))
                .andExpect(jsonPath("$.data.cancelEvent.status").value("CANCELLED"));
        graphql("{ eventOfId(id: \"" + id + "\") { status } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.eventOfId.status").value("CANCELLED"));
        graphql("mutation { cancelEvent(id: \"" + id + "\") { id status } }")
                .andExpect(jsonPath("$.errors").isNotEmpty());
        graphql("mutation { createEvent(input: {name: \"New event\", date: \"2021-01-01\", totalSpots: 10, partnerId: \""
                + partner.partnerId().value() + "\"}) { id name date totalSpots status } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createEvent.id").isString())
                .andExpect(jsonPath("$.data.createEvent.name").value("New event"))
                .andExpect(jsonPath("$.data.createEvent.totalSpots").value(10));
    }

    @Test
    void givenMissingEventWhenUsingGraphqlThenReturnNullForQueryAndErrorForCommand() throws Exception {
        var id = EventId.unique().value();
        graphql("{ eventOfId(id: \"" + id + "\") { id } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(content().json("{\"data\":{\"eventOfId\":null}}", true));
        graphql("mutation { cancelEvent(id: \"" + id + "\") { id status } }")
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    private org.springframework.test.web.servlet.ResultActions graphql(String query) throws Exception {
        var result = mvc.perform(post("/graphql").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("query", query))))
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
    }
}
