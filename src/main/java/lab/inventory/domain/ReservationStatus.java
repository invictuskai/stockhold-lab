package lab.inventory.domain;

/** ACTIVE -> CLAIMED / CANCELLED / EXPIRED；终态不可复活。 */
public enum ReservationStatus {
    ACTIVE,
    CLAIMED,
    CANCELLED,
    EXPIRED
}
