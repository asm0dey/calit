(function () {
  // Allowed-durations editor. Each [data-durations] form holds a [data-duration-list] tbody of
  // [data-duration-row] rows, plus a [data-duration-template] holding one blank row. Buttons:
  //   [data-add-duration]     append a blank row
  //   [data-remove-duration]  remove the row it sits in
  // The server bulk-saves parallel d.duration[]/d.before[]/d.after[] arrays and drops any row whose
  // duration is blank, so adding rows client-side needs no server change and removing one is the
  // same thing as clearing its duration field.
  //
  // The form also renders a trailing blank row server-side. That is the no-JS path -- an owner
  // without JavaScript still adds one length per save -- so this script must not remove it.

  function rowBox(form) {
    return form.querySelector("[data-duration-list]");
  }

  function makeRow(form) {
    let tpl = form.querySelector("[data-duration-template]");
    return tpl.content.firstElementChild.cloneNode(true);
  }

  // The "default" radio is keyed by ROW INDEX, because a length typed into a fresh row has no value
  // for the server to match on until it parses the form. Every add or remove shifts those indices,
  // so renumber after each one -- otherwise the radio points at the wrong row, or at a row that no
  // longer exists, and the save moves the default somewhere the owner did not click.
  function renumber(form) {
    let rows = form.querySelectorAll(
      "[data-duration-list] [data-duration-row]",
    );
    rows.forEach(function (row, i) {
      let radio = row.querySelector('[name="defaultRow"]');
      if (radio) {
        radio.value = String(i);
      }
    });
  }

  document.addEventListener("click", function (e) {
    let btn = e.target.closest("button");
    if (!btn) {
      return;
    }
    let form = btn.closest("[data-durations]");
    if (!form) {
      return;
    }

    if (Object.hasOwn(btn.dataset, "addDuration")) {
      e.preventDefault();
      let row = makeRow(form);
      rowBox(form).appendChild(row);
      renumber(form);
      let first = row.querySelector('[name="d.duration"]');
      if (first) {
        first.focus();
      }
    } else if (Object.hasOwn(btn.dataset, "removeDuration")) {
      e.preventDefault();
      let row = btn.closest("[data-duration-row]");
      // Never leave the owner with no way to add a length: keep at least one row standing, and
      // blank it instead of removing it. A blank row is dropped on save either way.
      if (rowBox(form).querySelectorAll("[data-duration-row]").length > 1) {
        let wasDefault = row.querySelector('[name="defaultRow"]')?.checked;
        row.remove();
        renumber(form);
        if (wasDefault) {
          // Never submit with no default selected: fall back to the first row, which the server
          // then reads as "leave the default where it is" if that row is blank.
          let first = form.querySelector(
            '[data-duration-list] [name="defaultRow"]',
          );
          if (first) {
            first.checked = true;
          }
        }
      } else {
        row.querySelectorAll("input").forEach(function (i) {
          i.value = "";
        });
      }
    }
  });
})();
