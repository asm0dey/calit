package site.asm0dey.calit.web;

import site.asm0dey.calit.i18n.AppMessages;

/**
 * The invitee-only timezone-picker bar + reformat script, and the booking-page calendar
 * enhancement script. Styling comes from Tailwind v4 + daisyUI 5 compiled to {@code /calit.css}.
 */
public final class Layout {

    private Layout() {}

    /**
     * Shared inline vanilla JS reused by every invitee-facing page that shows times
     * (the slot picker, the confirmation page, and the manage/reschedule page).
     *
     * <p>The server renders each time as an absolute instant in a {@code data-utc}
     * attribute (an ISO-8601 instant with offset or {@code Z}); this script reformats
     * every {@code [data-utc]} element into the VIEWER's local timezone (auto-detected,
     * overridable via {@code #tz-picker}). It NEVER changes any submitted value — the
     * booking form's hidden {@code startUtc} input keeps its absolute UTC instant, so the
     * display zone only changes the LABEL, never which instant is booked.</p>
     *
     * <p>The stable marker comment {@code CALIT_TZ_REFORMAT} lets @QuarkusTest assert the
     * script is present without executing it (RestAssured can't run JS).</p>
     */
    public static final String TZ_SCRIPT = """
            <script>
            /* CALIT_TZ_REFORMAT — viewer-local time reformatting (Calendly-standard) */
            (function () {
              /* CALIT_TZ_FULL_LIST — full canonical IANA list from the browser; curated fallback for pre-2022 browsers. */
              var ZONES;
              try { ZONES = Intl.supportedValuesOf('timeZone').slice(); }
              catch (e) {
                ZONES = [
                  'America/Los_Angeles','America/Denver','America/Chicago','America/New_York',
                  'America/Sao_Paulo','UTC','Europe/London','Europe/Amsterdam','Europe/Berlin',
                  'Europe/Paris','Europe/Madrid','Europe/Athens','Africa/Johannesburg',
                  'Asia/Dubai','Asia/Kolkata','Asia/Singapore','Asia/Tokyo',
                  'Australia/Sydney','Pacific/Auckland'
                ];
              }
              var detected = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
              if (ZONES.indexOf(detected) < 0) { ZONES.unshift(detected); }
              /* CALIT_TZ_INITIAL — the zone the page was AUTHORED in: the owner's stored zone on
                 /me (adminBase emits body[data-tz]), the viewer's own zone on the invitee pages
                 (which emit none). The picker starts here and stays an explicit override the
                 viewer can change; it is no longer a second, disagreeing default (calit-mhgs). */
              var initial = document.body.dataset.tz || detected;
              if (ZONES.indexOf(initial) < 0) { ZONES.unshift(initial); }

              var picker = document.getElementById('tz-picker');
              var label  = document.getElementById('tz-label');
              if (picker) {
                ZONES.forEach(function (z) {
                  var o = document.createElement('option');
                  o.value = z; o.textContent = z;
                  if (z === initial) { o.selected = true; }
                  picker.appendChild(o);
                });
              }

              /* Words (weekday, month, connector) follow the PAGE language... */
              var LANG = document.documentElement.lang || undefined;
              /* CALIT_HOUR_CYCLE — ...but 12h-vs-24h follows the VIEWER's device (issue #116).
                 documentElement.lang is region-less ('en' = US defaults = AM/PM for everyone).
                 resolvedOptions() only reports hourCycle when an hour field is requested. */
              var HC = new Intl.DateTimeFormat(undefined, {hour:'numeric'}).resolvedOptions().hourCycle;
              /* On /me the host may force a cycle; invitee pages never emit data-hc, so the
                 device keeps deciding there. Empty string means "auto". */
              var forcedHC = document.body.dataset.hc;
              if (forcedHC) { HC = forcedHC; }
              function render() {
                var tz = picker ? picker.value : initial;
                if (label) { label.textContent = tz; }
                document.querySelectorAll('[data-utc]').forEach(function (el) {
                  var d = new Date(el.dataset.utc);
                  var opts = (el.dataset.timeOnly === '1')
                    ? { timeStyle: 'short', timeZone: tz, hourCycle: HC }
                    : { dateStyle: 'full', timeStyle: 'short', timeZone: tz, hourCycle: HC };
                  el.textContent = d.toLocaleString(LANG, opts);
                });
              }
              if (picker) { picker.addEventListener('change', render); }
              render();
            })();
            </script>
            """;

    /**
     * Reusable, localized timezone-picker bar. TZ_SCRIPT fills the {@code <select>} client-side and
     * pre-selects the zone the page was AUTHORED in: {@code body[data-tz]} where the page emits one
     * (the owner's stored zone, on /me), otherwise the browser-detected zone (the invitee pages,
     * which emit none). The viewer can still change it; the selection is an override, not a second
     * disagreeing default (calit-mhgs).
     */
    public static String tzBar(AppMessages m) {
        return """
                <div class="tz-bar">
                  %s <strong><span id="tz-label">%s</span></strong>
                  <label style="display:inline">%s
                    <select id="tz-picker"></select>
                  </label>
                </div>
                """.formatted(m.tz_bar_shown_in(), m.tz_bar_local_default(), m.tz_bar_change());
    }

    /**
     * Vanilla-JS progressive enhancement for the booking/reschedule slot picker. The server
     * renders every available day as a <section class="day-slots" data-date="YYYY-MM-DD"> with
     * radio slots inside; with JS OFF they simply stack (graceful fallback). With JS ON this
     * builds a month calendar, hides all day sections, and reveals one day at a time on click.
     * It never alters any radio value — only which day section is visible.
     */
    public static final String CALENDAR_SCRIPT = """
            <script>
            /* CALIT_CALENDAR — two-pane calendar/time picker progressive enhancement */
            (function () {
              var cal = document.getElementById('calendar');
              var sections = Array.prototype.slice.call(document.querySelectorAll('.day-slots'));
              if (!cal || !sections.length) { return; }

              var byDate = {};
              sections.forEach(function (s) { byDate[s.dataset.date] = s; });
              var dates = Object.keys(byDate).sort();
              var LANG = document.documentElement.lang || undefined;
              var MONTHS = [];
              for (var mi = 0; mi < 12; mi++) {
                MONTHS.push(new Intl.DateTimeFormat(LANG, {month:'long'}).format(new Date(2021, mi, 1)));
              }
              // First weekday column from the viewer's *browser* locale (region-aware: en-US=Sun,
              // de-DE=Mon, he-IL=Sun). getWeekInfo().firstDay is 1=Mon..7=Sun; %7 maps to getDay()
              // index 0=Sun..6=Sat. Falls back to Monday on old browsers lacking Intl.Locale weekInfo.
              var FIRST = 1; // Monday fallback
              try { FIRST = new Intl.Locale(navigator.language).getWeekInfo().firstDay % 7; } catch (e) {}
              // 2021-08-01 is a Sunday, so new Date(2021,7,1+k) has getDay() === k.
              var DOW = [];
              for (var di = 0; di < 7; di++) {
                DOW.push(new Intl.DateTimeFormat(LANG, {weekday:'short'}).format(new Date(2021, 7, 1 + ((FIRST + di) % 7))));
              }

              function parse(iso) { var p = iso.split('-'); return new Date(+p[0], +p[1]-1, +p[2]); }
              function iso(d) {
                var m = ('0'+(d.getMonth()+1)).slice(-2), day = ('0'+d.getDate()).slice(-2);
                return d.getFullYear()+'-'+m+'-'+day;
              }
              var first = parse(dates[0]);
              var last = parse(dates[dates.length-1]);
              var view = new Date(first.getFullYear(), first.getMonth(), 1);
              var selected = dates[0];

              function show(date) {
                selected = date;
                sections.forEach(function (s) { s.hidden = (s.dataset.date !== date); });
                render();
              }

              function render() {
                var y = view.getFullYear(), m = view.getMonth();
                var prevDisabled = (y < first.getFullYear()) || (y === first.getFullYear() && m <= first.getMonth());
                var nextDisabled = (y > last.getFullYear()) || (y === last.getFullYear() && m >= last.getMonth());
                var html = '<div class="calendar__head">'
                  + '<button type="button" class="calendar__nav" data-step="-1"' + (prevDisabled?' disabled':'') + '>&lsaquo;</button>'
                  + '<span class="calendar__title">' + MONTHS[m] + ' ' + y + '</span>'
                  + '<button type="button" class="calendar__nav" data-step="1"' + (nextDisabled?' disabled':'') + '>&rsaquo;</button>'
                  + '</div><div class="calendar__grid">';
                DOW.forEach(function (d) { html += '<span class="calendar__dow">' + d + '</span>'; });
                var firstDow = ((new Date(y, m, 1).getDay() - FIRST) + 7) % 7; // blanks before day 1
                for (var i = 0; i < firstDow; i++) { html += '<span class="calendar__day calendar__day--empty"></span>'; }
                var days = new Date(y, m+1, 0).getDate();
                for (var d = 1; d <= days; d++) {
                  var key = iso(new Date(y, m, d));
                  var avail = !!byDate[key];
                  var cls = 'calendar__day' + (avail ? ' calendar__day--available' : '')
                          + (key === selected ? ' calendar__day--selected' : '');
                  html += '<button type="button" class="' + cls + '" data-date="' + key + '"'
                        + (avail ? '' : ' disabled') + '>' + d + '</button>';
                }
                html += '</div>';
                cal.innerHTML = html;
              }

              cal.addEventListener('click', function (e) {
                var nav = e.target.closest('.calendar__nav');
                if (nav && !nav.disabled) { view.setMonth(view.getMonth() + (+nav.dataset.step)); render(); return; }
                var day = e.target.closest('.calendar__day--available');
                if (day) { view = new Date(parse(day.dataset.date).getFullYear(), parse(day.dataset.date).getMonth(), 1); show(day.dataset.date); }
              });

              show(selected);
            })();
            </script>
            """;

    /**
     * Progressive enhancement for the co-host add input in {@code _hostlist.html}: with JS OFF the
     * plain {@code <input name=cohost>} still submits and is validated server-side (Task 6/17,
     * {@link site.asm0dey.calit.booking.MeetingHosts#eligibleCohost}); with JS ON this debounces
     * keystrokes and fills the {@code <datalist>} from {@code GET /me/hosts} so the browser offers
     * matching, eligible usernames. Never blocks or alters submission — only suggests.
     *
     * <p>The stable marker comment {@code CALIT_HOST_TYPEAHEAD} lets @QuarkusTest assert the
     * script is present without executing it (RestAssured can't run JS).
     */
    public static final String HOST_TYPEAHEAD_SCRIPT = """
            <script>
            /* CALIT_HOST_TYPEAHEAD - progressive-enhancement co-host autocomplete over a plain input */
            (function () {
              var form = document.querySelector('[data-host-add]');
              if (!form) return;
              var input = form.querySelector('input[name=cohost]');
              var list = form.querySelector('datalist');
              var typeId = form.getAttribute('action').split('/')[3];
              var t;
              input.addEventListener('input', function () {
                clearTimeout(t);
                var q = input.value.trim();
                if (q.length < 2) return;
                t = setTimeout(function () {
                  fetch('/me/hosts?typeId=' + typeId + '&q=' + encodeURIComponent(q))
                    .then(function (r) { return r.json(); })
                    .then(function (rows) {
                      list.innerHTML = '';
                      rows.forEach(function (row) {
                        var o = document.createElement('option');
                        o.value = row.username;
                        list.appendChild(o);
                      });
                    });
                }, 200);
              });
            })();
            </script>
            """;
}
