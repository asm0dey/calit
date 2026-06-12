(function () {
  // Weekly-schedule grid editor. Each [data-workplan] form contains seven [data-day]
  // rows; each row has a [data-frames] box of [data-frame] start/end pairs. Buttons:
  //   [data-add-frame="DAY"]      append a blank frame to that day
  //   [data-remove-frame]         remove the frame it sits in
  //   [data-copy-all="DAY"]       replace every other day's frames with DAY's
  //   [data-copy-weekdays="DAY"]  replace Mon–Fri frames with DAY's
  // The server bulk-saves parallel frameDay[]/frameStart[]/frameEnd[] arrays; each frame
  // carries a hidden frameDay so copied frames are reassigned to their target day.
  var WEEKDAYS = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'];

  function dayRow(grid, day) { return grid.querySelector('[data-day="' + day + '"]'); }
  function frameBox(row) { return row.querySelector('[data-frames]'); }

  function makeFrame(grid, day, start, end) {
    var tpl = grid.querySelector('[data-frame-template]');
    var node = tpl.content.firstElementChild.cloneNode(true);
    node.querySelector('[name="frameDay"]').value = day;
    if (start) { node.querySelector('[name="frameStart"]').value = start; }
    if (end) { node.querySelector('[name="frameEnd"]').value = end; }
    return node;
  }

  function readFrames(grid, day) {
    var out = [];
    frameBox(dayRow(grid, day)).querySelectorAll('[data-frame]').forEach(function (f) {
      out.push({
        start: f.querySelector('[name="frameStart"]').value,
        end: f.querySelector('[name="frameEnd"]').value
      });
    });
    return out;
  }

  function copyDay(grid, sourceDay, targets) {
    var frames = readFrames(grid, sourceDay);
    targets.forEach(function (day) {
      if (day === sourceDay) { return; }
      var box = frameBox(dayRow(grid, day));
      box.innerHTML = '';
      frames.forEach(function (fr) { box.appendChild(makeFrame(grid, day, fr.start, fr.end)); });
    });
  }

  function allDays(grid) {
    var days = [];
    grid.querySelectorAll('[data-day]').forEach(function (r) { days.push(r.getAttribute('data-day')); });
    return days;
  }

  document.addEventListener('click', function (e) {
    var btn = e.target.closest('button');
    if (!btn) { return; }
    var grid = btn.closest('[data-workplan]');
    if (!grid) { return; }

    if (btn.hasAttribute('data-add-frame')) {
      e.preventDefault();
      frameBox(dayRow(grid, btn.getAttribute('data-add-frame')))
        .appendChild(makeFrame(grid, btn.getAttribute('data-add-frame'), '', ''));
    } else if (btn.hasAttribute('data-remove-frame')) {
      e.preventDefault();
      btn.closest('[data-frame]').remove();
    } else if (btn.hasAttribute('data-copy-all')) {
      e.preventDefault();
      copyDay(grid, btn.getAttribute('data-copy-all'), allDays(grid));
    } else if (btn.hasAttribute('data-copy-weekdays')) {
      e.preventDefault();
      copyDay(grid, btn.getAttribute('data-copy-weekdays'), WEEKDAYS);
    }
  });
})();
