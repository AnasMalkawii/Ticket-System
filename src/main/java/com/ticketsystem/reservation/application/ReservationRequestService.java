package com.ticketsystem.reservation.application;

import org.springframework.stereotype.Service;

/** Chooses the ordinary transaction or burst micro-batching from explicit configuration. */
@Service
public class ReservationRequestService {

    private final ReserveTicketsService direct;
    private final ReservationBatcher batcher;
    private final ReservationBatchingProperties batching;

    public ReservationRequestService(ReserveTicketsService direct,
                                     ReservationBatcher batcher,
                                     ReservationBatchingProperties batching) {
        this.direct = direct;
        this.batcher = batcher;
        this.batching = batching;
    }

    public ReserveTicketsResult reserveWithResult(ReserveTicketsCommand command) {
        return batching.enabled()
                ? batcher.reserve(command)
                : direct.reserveWithResult(command);
    }
}
