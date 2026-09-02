package br.com.fullcycle.application.event;

import br.com.fullcycle.application.UseCase;
import br.com.fullcycle.domain.event.EventId;
import br.com.fullcycle.domain.event.EventRepository;
import br.com.fullcycle.domain.exceptions.ValidationException;
import br.com.fullcycle.domain.partner.PartnerId;
import br.com.fullcycle.domain.partner.PartnerRepository;

import java.util.Objects;

public class CancelEventUseCase extends UseCase<CancelEventUseCase.Input, CancelEventUseCase.Output> {

    private final EventRepository eventRepository;
    private final PartnerRepository partnerRepository;

    public CancelEventUseCase(final EventRepository eventRepository, final PartnerRepository partnerRepository) {
        this.eventRepository = Objects.requireNonNull(eventRepository);
        this.partnerRepository = Objects.requireNonNull(partnerRepository);
    }

    @Override
    public Output execute(final Input input) {
        final var partner = partnerRepository.partnerOfId(PartnerId.with(input.eventId))
                .orElseThrow(() -> new ValidationException("Partner not found"));

        final var event = eventRepository.eventOfId(EventId.with(input.eventId))
                .orElseThrow(() -> new ValidationException("Event not found"));

        if (!event.partnerId().equals(partner.partnerId())) {
            throw new ValidationException("Event cannot not ");
        }

        event.cancel();

        return new Output(partner.partnerId().toString(), true);
    }


    public record Input(String partnerId, String eventId) { }

    public record Output(String eventId, Boolean isCancelled) { }
}
