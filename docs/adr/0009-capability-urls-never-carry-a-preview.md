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
- The rendered card endpoints (`/og.png`, `/og/{user}.png`, `/og/{user}/{slug}.png`) are separate
  from the capability-URL pages this ADR governs, but they do return `Cache-Control: public`, so a
  `Set-Cookie` on those responses could be stored by a shared cache and served to another visitor.
  This was first written as a theoretical note about `quarkus-credential`; verifying the feature
  against a live instance showed it was actually happening, with a different cookie —
  `quarkus-rest-csrf` issues a `csrf-token` on safe GETs regardless of content type, so *every* card
  response carried one. Fixed in `calit-7hls` by a Vert.x route filter that removes that cookie on
  the three card routes only; the booking form still receives its token, which is what keeps CSRF
  protection intact. The general rule stands: a response this application marks `public` must not
  carry a `Set-Cookie`, and any new cacheable endpoint should be checked against that.
