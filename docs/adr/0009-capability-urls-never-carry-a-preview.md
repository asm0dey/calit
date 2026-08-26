# Capability URLs never carry a preview

A page reachable by holding an unguessable token — `/booking/{manageToken}/manage`,
`/guest/{declineToken}/decline` and their siblings — is authorised by the URL itself. Anyone the
link is forwarded to can open it, which is what makes it useful for an invitee who has no account.

Link previews turn that property into a leak. A chat client that unfurls such a URL paints the
invitee's name, the meeting and its time into the conversation for everyone in it, and some clients
prefetch previews server-side, so the endpoint is touched by machines nobody invited.

So: a capability URL emits `noindex,nofollow` and no `og:`/`twitter:` tags at all.

## Considered options

**A `noindex` flag on the token templates** — rejected: the safe behaviour would depend on every
future page remembering to set it, and the failure is silent.

**Blocking preview bots by user agent** — rejected: unfurlers are not required to identify
themselves, the list is unbounded, and a missed one leaks exactly the data this rule protects.

## Consequences

- `base.html` renders `og:`/`twitter:` tags only when a page passes it an `OgCard`; a page that
  passes nothing gets the suppression branch instead. Safety is therefore the default, not an
  opt-in — a new token-addressed page inherits `noindex,nofollow` and no preview automatically, and
  the leak can only reappear if someone deliberately opts that page in by supplying a card.
- The `/booking/{manageToken}/*` pages and `/guest/{declineToken}/decline` pass no `OgCard` and so
  render the suppression branch, the same as every other page that has never been wired up to emit
  one.
- Every future capability-URL route is safe by construction the moment it exists, without anyone
  needing to remember this rule — the safety comes from *not* opting in, not from a checklist item
  someone has to complete.
