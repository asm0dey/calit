// Progressive enhancement for the notification channel list. Without JS a host adds one channel
// per save and the re-render supplies a fresh empty card; with JS "+ Add another" clones that
// empty card so several go in at once. Same form, same POST, same handler.
document.querySelectorAll("[data-channel-add]").forEach(function (btn) {
  var blank = document.querySelector("[data-channel-new]");
  if (!blank) return;
  var template = blank.cloneNode(true);
  btn.classList.remove("hidden");
  btn.addEventListener("click", function () {
    var cards = document.querySelectorAll("[data-channel-new]");
    var last = cards[cards.length - 1];
    var copy = template.cloneNode(true);
    // Send test is keyed by the row's position in the repeated fields, so a clone must claim the
    // next index or its button would test the row above it.
    var index = document.querySelectorAll("[name=channelUrl]").length;
    copy.querySelectorAll("[name=testIndex]").forEach(function (b) {
      b.value = index;
    });
    last.parentNode.insertBefore(copy, last.nextSibling);
  });
});
