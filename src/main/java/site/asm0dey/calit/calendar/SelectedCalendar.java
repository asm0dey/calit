package site.asm0dey.calit.calendar;

import module java.base;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.*;

@Entity
@Table(name = "calendar")
public class SelectedCalendar extends PanacheEntityBase {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "owner_id", nullable = false)
    public Long ownerId;
    /**
     * The connected Google account this calendar belongs to.
     */
    @Column(name = "account_id", nullable = false)
    // pre-rename JSON key of /api/google/calendars/write-target
    @JsonProperty("googleCredentialId")
    public Long accountId;
    /**
     * The Google-side calendar id (often an email address or an opaque id).
     */
    @Column(name = "remote_id", nullable = false)
    // pre-rename JSON key of /api/google/calendars/write-target
    @JsonProperty("googleCalendarId")
    public String remoteId;
    @Column(nullable = false)
    public String summary;
    /**
     * Include this calendar's busy blocks when computing free/busy.
     */
    @Column(name = "read_for_busy", nullable = false)
    public boolean readForBusy = false;
    /**
     * Create new booking events on this calendar. At most one row per owner may have this true.
     */
    @Column(name = "write_target", nullable = false)
    public boolean writeTarget = false;
    /**
     * Whether this calendar can create Google Meet conferences ("hangoutsMeet" allowed). When the
     * write target can't, GOOGLE_MEET meeting types are forbidden so bookings don't 500 on Google's
     * "Invalid conference type value". Captured from calendarList at selection time.
     */
    @Column(name = "supports_meet", nullable = false)
    public boolean supportsMeet = true;

    /**
     * This owner's calendars whose busy time should be subtracted from availability.
     */
    public static List<SelectedCalendar> readForBusy(Long ownerId) {
        return list("ownerId = ?1 and readForBusy = true", ownerId);
    }

    /**
     * This owner's single write-target calendar, or null if none selected yet.
     */
    public static SelectedCalendar writeTarget(Long ownerId) {
        return find("ownerId = ?1 and writeTarget = true", ownerId).firstResult();
    }

    /**
     * This owner's calendar with the given Google id, or null.
     */
    public static SelectedCalendar findByRemoteId(Long ownerId, String remoteId) {
        return find("ownerId = ?1 and remoteId = ?2", ownerId, remoteId).firstResult();
    }

    /**
     * This owner's selected calendar with the given Google id on the given connected account, or
     * null. Unlike {@link #findByRemoteId(Long, String)} this disambiguates a calendar shared into
     * two accounts, which is exactly what a stored (credential, calendar) override names.
     */
    public static SelectedCalendar findOwned(Long ownerId, Long accountId, String remoteId) {
        if (accountId == null || remoteId == null) {
            return null;
        }
        return find("ownerId = ?1 and accountId = ?2 and remoteId = ?3", ownerId, accountId, remoteId).firstResult();
    }

    /**
     * This calendar as a {@code "accountId:remoteId"} form value — the encoding the Google
     * settings page already uses for its checkboxes/radios, and what the write-calendar pickers
     * submit. A method (not template string-concat) because Qute has no concatenation operator.
     */
    public String optionValue() {
        return accountId + ":" + remoteId;
    }

    /**
     * Remove all of this owner's calendar selections (used before re-saving).
     */
    public static long deleteForOwner(Long ownerId) {
        return delete("ownerId", ownerId);
    }

    /**
     * This owner's read-for-busy calendars grouped by the credential (account) they belong to.
     */
    public static Map<Long, List<SelectedCalendar>> readForBusyByAccount(Long ownerId, CalendarProvider provider) {
        return readForBusy(ownerId, provider).stream().collect(Collectors.groupingBy(c -> c.accountId));
    }

    /**
     * This owner's read-for-busy calendars on accounts of {@code provider}.
     */
    public static List<SelectedCalendar> readForBusy(Long ownerId, CalendarProvider provider) {
        var accountEntity = switch (provider) {
            case GOOGLE -> "GoogleCredential";
            case CALDAV -> "CaldavAccount";
        };
        return list(
                "ownerId = ?1 and readForBusy = true and accountId in (select a.id from "
                + accountEntity
                + " a where a.ownerId = ?1)",
                ownerId
        );
    }
}
