---
title: DPA template
description: A starting point for a data processing agreement when you host calit for someone else.
---

:::caution[Starting point only]
This is a starting point for a lawyer, not a document to sign as it is.
:::

Use this when you run a calit deployment **on someone else's behalf**: an agency hosting it for a
client, or an IT team hosting it for another company. The client is the controller; you are the
processor. If you run calit for yourself, you do not need a DPA with yourself; see the
[operator guide](/calit/compliance/operator-guide/) instead.

---

## Data processing agreement

**Between** _[client name and address]_ ("Controller")
**and** _[your name and address]_ ("Processor").

### 1. Subject matter and duration

The Processor hosts and operates a calit scheduling service at _[URL]_ for the Controller, for the
term of _[main agreement]_.

### 2. Nature and purpose of processing

Hosting, storing and transmitting booking data so the Controller's users can offer bookable meeting
times, and sending the related email and notifications.

### 3. Categories of data subjects and data

As listed in the Controller's [records of processing](/calit/compliance/records-of-processing/):
invitees, invitees' guests, and the Controller's own account holders.

Special categories of data (Art. 9): _[none expected / list]_. The Controller is responsible for not
asking for such data in custom booking fields unless it has a lawful basis.

### 4. Processor obligations

The Processor will:

1. process personal data only on the Controller's documented instructions;
2. ensure that people with access to the data are bound by confidentiality;
3. apply the security measures in Annex 1;
4. engage sub-processors only as listed in Annex 2, and inform the Controller of any change
   _[n]_ days in advance;
5. help the Controller answer data subject requests. The software provides self-service download
   and erasure on each booking's manage link, and export and deletion for account holders; requests
   outside those are handled by the Processor within _[n]_ days;
6. notify the Controller of a personal data breach without undue delay and within _[n]_ hours of
   becoming aware of it (see the [breach checklist](/calit/compliance/breach-checklist/));
7. configure the retention window the Controller sets: _[n days after the meeting ends / none]_;
8. at the end of the service, delete or return all personal data as the Controller chooses, and
   delete existing copies, including backups within _[n]_ days;
9. make available the information needed to demonstrate compliance, and allow audits _[terms]_.

### 5. Limits the Controller accepts

Erasure in the software cannot reach copies that have left the server: delivered email, messages
already sent to notification channels, calendar invites in recipients' calendars, and Google's
~30-day trash for deleted events. Account deletion does not revoke a Google authorisation; the
account holder does that in their Google account.

### Annex 1: Security measures

- Hosting location: _[operator]_
- Encryption in transit: TLS _[details]_
- Encryption at rest: application-level for OAuth tokens and notification-channel URLs; disk or
  database encryption _[details]_
- Access control to servers and database: _[details]_
- Backups and their retention: _[details]_
- Logging: `PRIVACY` and `audit` log lines kept for _[period]_

### Annex 2: Sub-processors

See the [sub-processor list](/calit/compliance/sub-processors/): _[attach the completed table]_.

**Signatures**: _[Controller]_ _[Processor]_ _[date]_
