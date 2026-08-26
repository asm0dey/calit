-- A zero or negative meeting-type duration made SlotService's cadence zero, so its slot loops could
-- never advance -- an unbounded allocation loop that pinned the request thread until the heap gave
-- out (calit-xjrg). The form now refuses one, and meeting_type_duration has carried
-- `check (duration_minutes > 0)` since V29; this brings the parent column in line.

-- Repair before constraining. A row like this is already broken -- every public page render for that
-- meeting type hangs -- so moving it to a usable length is strictly better than leaving it, and far
-- better than a CHECK that fails validation and takes the whole application down at boot on someone
-- else's data. 30 minutes is the value the create form itself defaults to.
update meeting_type set duration_minutes = 30 where duration_minutes <= 0;

alter table meeting_type add constraint meeting_type_duration_positive check (duration_minutes > 0);
