package site.asm0dey.calit.email;

import java.time.Instant;

/**
 * Immutable description of one calendar event to render as an .ics. Built via the fluent
 * {@link #builder()} so call sites read by name instead of passing 10 positional args.
 * Defaults match an invitee/owner invitation: METHOD:REQUEST, SEQUENCE 0, RSVP requested.
 */
public record IcsEvent(
        String uid,
        String summary,
        String description,
        String location,
        IcsBuilder.Party organizer,
        IcsBuilder.Party attendee,
        Instant start,
        Instant end,
        IcsMethod method,
        int sequence,
        boolean attendeeRsvp) {

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String uid;
        private String summary;
        private String description;
        private String location;
        private IcsBuilder.Party organizer;
        private IcsBuilder.Party attendee;
        private Instant start;
        private Instant end;
        private IcsMethod method = IcsMethod.REQUEST;
        private int sequence = 0;
        private boolean attendeeRsvp = true;

        public Builder uid(String uid) {
            this.uid = uid;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder location(String location) {
            this.location = location;
            return this;
        }

        public Builder organizer(IcsBuilder.Party organizer) {
            this.organizer = organizer;
            return this;
        }

        public Builder attendee(IcsBuilder.Party attendee) {
            this.attendee = attendee;
            return this;
        }

        public Builder start(Instant start) {
            this.start = start;
            return this;
        }

        public Builder end(Instant end) {
            this.end = end;
            return this;
        }

        public Builder method(IcsMethod method) {
            this.method = method;
            return this;
        }

        public Builder sequence(int sequence) {
            this.sequence = sequence;
            return this;
        }

        public Builder attendeeRsvp(boolean attendeeRsvp) {
            this.attendeeRsvp = attendeeRsvp;
            return this;
        }

        public IcsEvent build() {
            return new IcsEvent(
                    uid,
                    summary,
                    description,
                    location,
                    organizer,
                    attendee,
                    start,
                    end,
                    method,
                    sequence,
                    attendeeRsvp);
        }
    }
}
