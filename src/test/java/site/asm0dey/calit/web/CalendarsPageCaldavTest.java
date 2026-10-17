package site.asm0dey.calit.web;

import module java.base;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import site.asm0dey.calit.caldav.CaldavClient;
import site.asm0dey.calit.caldav.CaldavException;
import site.asm0dey.calit.calendar.CaldavAccount;
import site.asm0dey.calit.calendar.CalendarListPort.RemoteCalendar;
import site.asm0dey.calit.calendar.SelectedCalendar;
import site.asm0dey.calit.domain.MeetingType;
import site.asm0dey.calit.google.GoogleCalendarListPort;
import site.asm0dey.calit.google.GoogleCredential;
import site.asm0dey.calit.user.TestOwners;

@QuarkusTest
class CalendarsPageCaldavTest {
    private static final String SERVER = "https://dav.example.com/";
    private static final String WORK = "https://dav.example.com/cal/alice/work/";
    @InjectMock
    CaldavClient caldav;
    @InjectMock
    GoogleCalendarListPort google;
    @Inject
    EntityManager em;

    private void discovers() {
        when(caldav.discover(anyString(), anyString(), anyString()))
            .thenReturn(
                    new CaldavClient.Discovery(
                            URI.create("https://dav.example.com/p/alice/"),
                            URI.create("https://dav.example.com/cal/alice/")
                    )
            );
        when(caldav.listCalendars(any())).thenReturn(List.of(new RemoteCalendar(WORK, "Work", false)));
    }

    private static io.restassured.response.ValidatableResponse connect(String password) {
        return connect(SERVER, password);
    }

    private static io.restassured.response.ValidatableResponse connect(String serverUrl, String password) {
        return given()
            .cookie("quarkus-credential", FormAuth.login())
            .redirects()
            .follow(false)
            .contentType("application/x-www-form-urlencoded")
            .formParam("serverUrl", serverUrl)
            .formParam("username", "alice")
            .formParam("password", password)
            .when()
            .post("/me/calendars/caldav")
            .then();
    }

    @Test
    void connectStoresAccountAndListsCalendars() {
        discovers();
        connect("secret").statusCode(303).header("Location", endsWith("/me/calendars"));

        CaldavAccount a =
                QuarkusTransaction
            .requiringNew()
            .call(() -> CaldavAccount.<CaldavAccount>findAll().firstResult());
        assertThat(a).isNotNull();
        assertThat(a.ownerId).isEqualTo(1L);
        assertThat(a.username).isEqualTo("alice");
        assertThat(a.password).isEqualTo("secret");
        assertThat(a.principalUrl).isEqualTo("https://dav.example.com/p/alice/");
        assertThat(a.homeUrl).isEqualTo("https://dav.example.com/cal/alice/");

        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/calendars")
            .then()
            .statusCode(200)
            .body(containsString("alice @ dav.example.com"))
            .body(containsString("Work"))
            .body(containsString("value=\"" + a.id + ":" + WORK + "\""));
    }

    @Test
    void reconnectingTheSamePrincipalUpdatesThePassword() {
        discovers();
        connect("old").statusCode(303);
        connect("new").statusCode(303);

        List<CaldavAccount> all = QuarkusTransaction
            .requiringNew()
            .call(() -> CaldavAccount.<CaldavAccount>listAll());
        assertThat(all)
            .singleElement()
            .extracting(a -> a.password)
            .isEqualTo("new");
    }

    @Test
    void privateHostShowsInlineErrorAndStoresNothing() {
        when(caldav.discover(anyString(), anyString(), anyString()))
            .thenThrow(new CaldavException(CaldavException.Reason.PRIVATE_HOST, "private"));
        connect("secret").statusCode(400).body(containsString("private network address"));

        assertThat(QuarkusTransaction
            .requiringNew()
            .<Long>call(() -> CaldavAccount.count())).isZero();
    }

    @Test
    void unsafeServerUrlsAreRefusedWithoutEchoingCredentials() {
        for (var url :
                List.of(
                        "https://user:p4ssw0rd@dav.example.com/",
                        "ftp://dav.example.com/",
                        "dav.example.com/no-scheme",
                        "https://dav.example.com/?q=1",
                        "https://dav.example.com/#frag",
                        "https:///nohost"
        )) {
            connect(url, "secret")
                .statusCode(400)
                .body(containsString("No CalDAV calendars found"))
                .body(not(containsString("p4ssw0rd")));
        }
        assertThat(QuarkusTransaction
            .requiringNew()
            .<Long>call(() -> CaldavAccount.count())).isZero();
    }

    @Test
    void storesTheNormalizedServerUrl() {
        discovers();
        connect("HTTPS://Dav.Example.com/./dav/../dav/", "secret").statusCode(303);
        assertThat(
                QuarkusTransaction
                    .requiringNew()
                    .<CaldavAccount>call(() -> CaldavAccount.<CaldavAccount>findAll().firstResult()).serverUrl
        )
            .isEqualTo("HTTPS://Dav.Example.com/dav/");
    }

    @Test
    void wrongPasswordShowsInlineError() {
        when(caldav.discover(anyString(), anyString(), anyString()))
            .thenThrow(new CaldavException(CaldavException.Reason.UNAUTHORIZED, "401"));
        connect("s3cr3t-Pa55")
            .statusCode(400)
            .body(containsString("rejected that username or password"))
            .body(not(containsString("s3cr3t-Pa55")))
            // the typed URL survives the bounce
            .body(containsString("value=\"" + SERVER + "\""));

        assertThat(QuarkusTransaction
            .requiringNew()
            .<Long>call(() -> CaldavAccount.count())).isZero();
    }

    @Test
    void passwordUpdateReverifiesAndClearsReconnectState() {
        discovers();
        Long id = seedCaldav(1L, true);
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .redirects()
            .follow(false)
            .contentType("application/x-www-form-urlencoded")
            .formParam("password", "fresh")
            .when()
            .post("/me/calendars/caldav/" + id + "/password")
            .then()
            .statusCode(303);

        CaldavAccount a = QuarkusTransaction
            .requiringNew()
            .call(() -> CaldavAccount.findById(id));
        assertThat(a.password).isEqualTo("fresh");
        assertThat(a.needsReconnect).isFalse();
        assertThat(a.reconnectNotifiedAt).isNull();
    }

    @Test
    void wrongNewPasswordKeepsTheOldOne() {
        when(caldav.discover(anyString(), anyString(), anyString()))
            .thenThrow(new CaldavException(CaldavException.Reason.UNAUTHORIZED, "401"));
        Long id = seedCaldav(1L, true);
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("password", "nope")
            .when()
            .post("/me/calendars/caldav/" + id + "/password")
            .then()
            .statusCode(400)
            .body(containsString("rejected that username or password"));

        CaldavAccount a = QuarkusTransaction
            .requiringNew()
            .call(() -> CaldavAccount.findById(id));
        assertThat(a.password).isEqualTo("secret");
        assertThat(a.needsReconnect).isTrue();
    }

    @Test
    void oldGooglePathRedirects() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .redirects()
            .follow(false)
            .when()
            .get("/me/google")
            .then()
            .statusCode(303)
            .header("Location", endsWith("/me/calendars"));
    }

    @Test
    void cannotDeleteAnotherOwnersAccount() {
        QuarkusTransaction
            .requiringNew()
            .run(() -> TestOwners.ensure(em, 2L));
        Long foreign = seedCaldav(2L, false);
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .when()
            .post("/me/calendars/accounts/" + foreign + "/delete")
            .then()
            .statusCode(404);

        assertThat(QuarkusTransaction
            .requiringNew()
            .<CaldavAccount>call(() -> CaldavAccount.findById(foreign))).isNotNull();
    }

    @Test
    void deletesOwnCaldavAccount() {
        Long id = seedCaldav(1L, false);
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .redirects()
            .follow(false)
            .contentType("application/x-www-form-urlencoded")
            .when()
            .post("/me/calendars/accounts/" + id + "/delete")
            .then()
            .statusCode(303);

        assertThat(QuarkusTransaction
            .requiringNew()
            .<Long>call(() -> CaldavAccount.count())).isZero();
    }

    @Test
    void savingTheSelectionKeepsBothProvidersCalendars() {
        discovers();
        Long dav = seedCaldav(1L, false);
        Long gcred = seedGoogle();
        when(google.listCalendars(any(GoogleCredential.class)))
            .thenReturn(List.of(new RemoteCalendar("g@example.com", "G", true)));
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .redirects()
            .follow(false)
            .contentType("application/x-www-form-urlencoded")
            .formParam("read", dav + ":" + WORK)
            .formParam("read", gcred + ":g@example.com")
            .formParam("writeTarget", gcred + ":g@example.com")
            .when()
            .post("/me/calendars/calendars")
            .then()
            .statusCode(303);

        List<SelectedCalendar> saved =
                QuarkusTransaction
            .requiringNew()
            .call(() -> SelectedCalendar.<SelectedCalendar>list("ownerId", 1L));
        assertThat(saved)
            .extracting(c -> c.accountId + ":" + c.remoteId + ":" + c.writeTarget)
            .containsExactlyInAnyOrder(dav + ":" + WORK + ":false", gcred + ":g@example.com:true");
    }

    @Test
    void caldavWriteTargetWithTooLongHrefIsRefused() {
        Long dav = seedCaldav(1L, false);
        // path "/" + 214 + "/" = 216 chars; + a 36-char uuid + ".ics" = 256 > 255
        String longHref = "https://dav.example.com/" + "a".repeat(214) + "/";
        when(caldav.listCalendars(any())).thenReturn(List.of(new RemoteCalendar(longHref, "Long", false)));
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("read", dav + ":" + longHref)
            .formParam("writeTarget", dav + ":" + longHref)
            .when()
            .post("/me/calendars/calendars")
            .then()
            .statusCode(400)
            .body(containsString("address is too long"));

        assertThat(QuarkusTransaction
            .requiringNew()
            .call(() -> SelectedCalendar.writeTarget(1L))).isNull();
    }

    @Test
    void caldavWriteTargetAtTheLengthLimitIsAccepted() {
        Long dav = seedCaldav(1L, false);
        // one char shorter than the refused case: event paths are exactly 255 chars
        String href = "https://dav.example.com/" + "a".repeat(213) + "/";
        when(caldav.listCalendars(any())).thenReturn(List.of(new RemoteCalendar(href, "Edge", false)));
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .redirects()
            .follow(false)
            .contentType("application/x-www-form-urlencoded")
            .formParam("read", dav + ":" + href)
            .formParam("writeTarget", dav + ":" + href)
            .when()
            .post("/me/calendars/calendars")
            .then()
            .statusCode(303);

        assertThat(QuarkusTransaction
            .requiringNew()
            .call(() -> SelectedCalendar.writeTarget(1L)))
            .extracting(c -> c.remoteId)
            .isEqualTo(href);
    }

    @Test
    void noWriteCalendarNoticeShownWhenAccountsButNoWriteTarget() {
        discovers();
        seedCaldav(1L, false);
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/calendars")
            .then()
            .statusCode(200)
            .body(containsString("No calendar is selected for writing"));
    }

    @Test
    void noWriteCalendarNoticeHiddenWithoutAccounts() {
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/calendars")
            .then()
            .statusCode(200)
            .body(containsString("Connect a CalDAV account"))
            .body(not(containsString("No calendar is selected for writing")));
    }

    @Test
    void meetWarningShownForCaldavWriteTarget() {
        Long dav = seedCaldav(1L, false);
        Long typeId = QuarkusTransaction.requiringNew().call(() -> {
            var c = new SelectedCalendar();
            c.ownerId = 1L;
            c.accountId = dav;
            c.remoteId = WORK;
            c.summary = "Work";
            c.readForBusy = true;
            c.writeTarget = true;
            c.supportsMeet = false;
            c.persist();
            var t = new MeetingType();
            t.ownerId = 1L;
            t.name = "Meet on CalDAV";
            t.slug = "meet-caldav";
            t.durationMinutes = 30;
            t.locationType = MeetingType.LocationType.GOOGLE_MEET;
            t.persist();
            return t.id;
        });
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/meeting-types/" + typeId)
            .then()
            .statusCode(200)
            .body(containsString("data-meet-unavailable"));
    }

    @Test
    void savingWithNoWriteCalendarPersistsNoneAndShowsTheNotice() {
        discovers();
        Long dav = seedCaldav(1L, false);
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .redirects()
            .follow(false)
            .contentType("application/x-www-form-urlencoded")
            .formParam("read", dav + ":" + WORK)
            .formParam("writeTarget", "")
            .when()
            .post("/me/calendars/calendars")
            .then()
            .statusCode(303);

        assertThat(QuarkusTransaction
            .requiringNew()
            .call(() -> SelectedCalendar.writeTarget(1L))).isNull();
        assertThat(QuarkusTransaction
            .requiringNew()
            .<Long>call(() -> SelectedCalendar.count("ownerId = ?1 and readForBusy = true", 1L))
        )
            .isOne();
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/calendars")
            .then()
            .statusCode(200)
            .body(containsString("No calendar is selected for writing"))
            .body(containsString("name=\"writeTarget\" value=\"\""));
    }

    @Test
    void disconnectingTheWriteTargetAccountLeavesNoWriteCalendar() {
        Long dav = seedCaldav(1L, false);
        seedGoogle();
        QuarkusTransaction.requiringNew().run(() -> {
            var c = new SelectedCalendar();
            c.ownerId = 1L;
            c.accountId = dav;
            c.remoteId = WORK;
            c.summary = "Work";
            c.readForBusy = true;
            c.writeTarget = true;
            c.persist();
        });
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .redirects()
            .follow(false)
            .contentType("application/x-www-form-urlencoded")
            .when()
            .post("/me/calendars/accounts/" + dav + "/delete")
            .then()
            .statusCode(303);

        assertThat(QuarkusTransaction
            .requiringNew()
            .call(() -> SelectedCalendar.writeTarget(1L))).isNull();
    }

    @Test
    void unauthorizedListingFlagsTheAccountForReconnect() {
        Long dav = seedCaldav(1L, false);
        when(caldav.listCalendars(any())).thenThrow(new CaldavException(CaldavException.Reason.UNAUTHORIZED, "401"));
        given().cookie("quarkus-credential", FormAuth.login()).when().get("/me/calendars").then().statusCode(200);

        assertThat(
                QuarkusTransaction
                    .requiringNew()
                    .<CaldavAccount>call(() -> CaldavAccount.findById(dav)).needsReconnect
        )
            .isTrue();
    }

    @Test
    void unexpectedListingFailureOnConnectShowsGenericErrorAndStoresNothing() {
        when(caldav.discover(anyString(), anyString(), anyString()))
            .thenReturn(
                    new CaldavClient.Discovery(
                            URI.create("https://dav.example.com/p/alice/"),
                            URI.create("https://dav.example.com/cal/alice/")
                    )
            );
        when(caldav.listCalendars(any())).thenThrow(new IllegalArgumentException("bad home"));
        connect("secret").statusCode(400).body(containsString("answered with an error"));

        assertThat(QuarkusTransaction
            .requiringNew()
            .<Long>call(() -> CaldavAccount.count())).isZero();
    }

    @Test
    void cannotUpdateAnotherOwnersPassword() {
        QuarkusTransaction
            .requiringNew()
            .run(() -> TestOwners.ensure(em, 2L));
        Long foreign = seedCaldav(2L, false);
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("password", "x")
            .when()
            .post("/me/calendars/caldav/" + foreign + "/password")
            .then()
            .statusCode(404);

        assertThat(
                QuarkusTransaction
                    .requiringNew()
                    .<CaldavAccount>call(() -> CaldavAccount.findById(foreign)).password
        )
            .isEqualTo("secret");
    }

    @Test
    void passwordUpdateForAnAccountDeletedMeanwhileIs404() {
        Long id = seedCaldav(1L, true);
        when(caldav.discover(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            QuarkusTransaction
                .requiringNew()
                .run(() -> CaldavAccount.deleteById(id));
            return new CaldavClient.Discovery(
                    URI.create("https://dav.example.com/p/alice/"),
                    URI.create("https://dav.example.com/cal/alice/")
            );
        });
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .contentType("application/x-www-form-urlencoded")
            .formParam("password", "fresh")
            .when()
            .post("/me/calendars/caldav/" + id + "/password")
            .then()
            .statusCode(404);
    }

    @Test
    void unnamedGoogleAccountGetsALocalizedFallbackLabel() {
        QuarkusTransaction.requiringNew().run(() -> {
            var g = new GoogleCredential();
            g.ownerId = 1L;
            g.refreshToken = "rt";
            g.googleSub = "sub-noemail";
            g.needsReconnect = true;
            g.persist();
        });
        given()
            .cookie("quarkus-credential", FormAuth.login())
            .when()
            .get("/me/calendars")
            .then()
            .statusCode(200)
            .body(containsString("Unnamed account"));
    }

    private static Long seedCaldav(Long ownerId, boolean needsReconnect) {
        return QuarkusTransaction.requiringNew().call(() -> {
            var a = new CaldavAccount();
            a.ownerId = ownerId;
            a.serverUrl = SERVER;
            a.username = "alice";
            a.password = "secret";
            a.principalUrl = "https://dav.example.com/p/alice/";
            a.homeUrl = "https://dav.example.com/cal/alice/";
            a.needsReconnect = needsReconnect;
            a.reconnectNotifiedAt = needsReconnect ? Instant.now() : null;
            a.persist();
            return a.id;
        });
    }

    private static Long seedGoogle() {
        return QuarkusTransaction.requiringNew().call(() -> {
            var g = new GoogleCredential();
            g.ownerId = 1L;
            g.refreshToken = "rt";
            g.googleSub = "sub-g";
            g.accountEmail = "g@example.com";
            g.persist();
            return g.id;
        });
    }
}
