-- The reason an adjuster picked when overriding eligibility (RecordEligibility.override_reason), shown with the
-- override next to the comment.
ALTER TABLE eligibility_check ADD COLUMN override_reason varchar(200);
