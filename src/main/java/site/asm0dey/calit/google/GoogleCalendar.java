package site.asm0dey.calit.google;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Entity
@Table(name = "google_calendar")
public class GoogleCalendar extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "owner_id", nullable = false)
    public Long ownerId;

    /** The connected Google account this calendar belongs to. */
    @Column(name = "google_credential_id", nullable = false)
    public Long googleCredentialId;

    /** The Google-side calendar id (often an email address or an opaque id). */
    @Column(name = "google_calendar_id", nullable = false)
    public String googleCalendarId;

    @Column(nullable = false)
    public String summary;

    /** Include this calendar's busy blocks when computing free/busy. */
    @Column(name = "read_for_busy", nullable = false)
    public boolean readForBusy = false;

    /** Create new booking events on this calendar. At most one row per owner may have this true. */
    @Column(name = "write_target", nullable = false)
    public boolean writeTarget = false;

    /**
     * Whether this calendar can create Google Meet conferences ("hangoutsMeet" allowed). When the
     * write target can't, GOOGLE_MEET meeting types are forbidden so bookings don't 500 on Google's
     * "Invalid conference type value". Captured from calendarList at selection time.
     */
    @Column(name = "supports_meet", nullable = false)
    public boolean supportsMeet = true;

    /** This owner's calendars whose busy time should be subtracted from availability. */
    public static List<GoogleCalendar> readForBusy(Long ownerId) {
        return list("ownerId = ?1 and readForBusy = true", ownerId);
    }

    /** This owner's single write-target calendar, or null if none selected yet. */
    public static GoogleCalendar writeTarget(Long ownerId) {
        return find("ownerId = ?1 and writeTarget = true", ownerId).firstResult();
    }

    /**
     * True when this owner has a write target that CANNOT mint Google Meet links, so GOOGLE_MEET
     * meeting types must be forbidden. False when there is no write target yet (don't over-block:
     * an owner may connect/pick a Meet-capable calendar later) or when the target supports Meet.
     */
    public static boolean writeTargetBlocksMeet(Long ownerId) {
        var wt = writeTarget(ownerId);
        return wt != null && !wt.supportsMeet;
    }

    /** This owner's calendar with the given Google id, or null. */
    public static GoogleCalendar findByGoogleId(Long ownerId, String googleCalendarId) {
        return find("ownerId = ?1 and googleCalendarId = ?2", ownerId, googleCalendarId)
                .firstResult();
    }

    /** Remove all of this owner's calendar selections (used before re-saving). */
    public static long deleteForOwner(Long ownerId) {
        return delete("ownerId", ownerId);
    }

    /** This owner's read-for-busy calendars grouped by the credential (account) they belong to. */
    public static Map<Long, List<GoogleCalendar>> readForBusyByCredential(Long ownerId) {
        return readForBusy(ownerId).stream().collect(Collectors.groupingBy(c -> c.googleCredentialId));
    }
}
