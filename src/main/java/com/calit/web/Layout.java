package com.calit.web;

/**
 * Shared inline CSS (all pages) plus the invitee-only timezone-picker bar and reformat
 * script. Owner/admin pages use only {@link #CSS}; the invitee pages additionally use
 * {@link #TZ_BAR}/{@link #TZ_SCRIPT} to relabel times into the viewer's local zone.
 */
public final class Layout {

    private Layout() {}

    public static final String CSS = """
            body{font-family:system-ui,sans-serif;max-width:760px;margin:2rem auto;padding:0 1rem;color:#222}
            a{color:#2563eb}
            .card{border:1px solid #ddd;border-radius:8px;padding:1rem;margin:.75rem 0}
            .badge{background:#fde68a;border-radius:4px;padding:.1rem .4rem;font-size:.75rem}
            .slot{display:inline-block;margin:.2rem;padding:.4rem .6rem;border:1px solid #2563eb;border-radius:6px}
            nav a{margin-right:1rem}
            label{display:block;margin:.5rem 0}
            input,select{padding:.3rem}
            .err{color:#b91c1c}
            button{padding:.4rem .8rem;cursor:pointer}
            .tz-bar{margin:.5rem 0;font-size:.9rem;color:#444}
            """;

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
              var ZONES = [
                'America/Los_Angeles','America/Denver','America/Chicago','America/New_York',
                'America/Sao_Paulo','UTC','Europe/London','Europe/Amsterdam','Europe/Berlin',
                'Europe/Paris','Europe/Madrid','Europe/Athens','Africa/Johannesburg',
                'Asia/Dubai','Asia/Kolkata','Asia/Singapore','Asia/Tokyo',
                'Australia/Sydney','Pacific/Auckland'
              ];
              var detected = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
              if (ZONES.indexOf(detected) < 0) { ZONES.unshift(detected); }

              var picker = document.getElementById('tz-picker');
              var label  = document.getElementById('tz-label');
              if (!picker) { return; }
              ZONES.forEach(function (z) {
                var o = document.createElement('option');
                o.value = z; o.textContent = z;
                if (z === detected) { o.selected = true; }
                picker.appendChild(o);
              });

              function render() {
                var tz = picker.value;
                if (label) { label.textContent = tz; }
                document.querySelectorAll('[data-utc]').forEach(function (el) {
                  var d = new Date(el.dataset.utc);
                  var opts = (el.dataset.timeOnly === '1')
                    ? { timeStyle: 'short', timeZone: tz }
                    : { dateStyle: 'full', timeStyle: 'short', timeZone: tz };
                  el.textContent = d.toLocaleString([], opts);
                });
              }
              picker.addEventListener('change', render);
              render();
            })();
            </script>
            """;

    /** Reusable timezone-picker bar (detected zone selected client-side by TZ_SCRIPT). */
    public static final String TZ_BAR = """
            <div class="tz-bar">
              Times shown in: <strong><span id="tz-label">your local time</span></strong>
              <label style="display:inline">Change:
                <select id="tz-picker"></select>
              </label>
            </div>
            """;

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
              var DOW = ['Mo','Tu','We','Th','Fr','Sa','Su'];
              var MONTHS = ['January','February','March','April','May','June','July','August',
                            'September','October','November','December'];

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
                var firstDow = (new Date(y, m, 1).getDay() + 6) % 7; // Mon=0
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
}
