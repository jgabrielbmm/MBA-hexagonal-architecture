package br.com.fullcycle.infrastructure.rest.presenters;

import br.com.fullcycle.application.Presenter;
import br.com.fullcycle.application.event.GetEventByIdUseCase;
import br.com.fullcycle.infrastructure.dtos.PublicEventResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component("publicGetEvent")
public class PublicGetEventByIdString implements Presenter<Optional<GetEventByIdUseCase.Output>, Object> {

    private static final Logger LOG = LoggerFactory.getLogger(PublicGetEventByIdString.class);

    @Override
    public ResponseEntity<?> present(Optional<GetEventByIdUseCase.Output> output) {
        return output
                .map(event -> ResponseEntity.ok()
                        .header("X-Public", "true")
                        .body(new PublicEventResponseDTO(
                                event.id(),
                                event.status()
                        )))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public String present(Throwable error) {
        LOG.error("An error was observer at PublicGetEventByIdString", error);
        return "not found";
    }
}
