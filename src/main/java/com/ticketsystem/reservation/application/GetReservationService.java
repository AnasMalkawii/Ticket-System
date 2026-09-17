package com.ticketsystem.reservation.application;

import com.ticketsystem.reservation.domain.Reservation;
import com.ticketsystem.reservation.domain.ReservationNotFoundException;
import com.ticketsystem.reservation.repository.ReservationRepository;
import com.ticketsystem.auth.exception.ForbiddenOperationException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads reservation state while enforcing owner-or-admin access in the service layer. */
@Service
public class GetReservationService {

    private final ReservationRepository reservationRepository;

    public GetReservationService(ReservationRepository reservationRepository) {
        this.reservationRepository = reservationRepository;
    }

    @Transactional(readOnly = true)
    public Reservation get(UUID reservationId, UUID actorUserId, boolean admin) {
        Reservation reservation = reservationRepository.findById(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        if (!admin && !reservation.getUserId().equals(actorUserId)) {
            throw new ForbiddenOperationException(reservationId);
        }
        return reservation;
    }
}
