# Changelog

All notable changes to the `koreafilings` Python SDK are documented in
this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this package adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Nothing pending.

## [0.3.3] — 2026-07-23

### Added

- `Summary` now surfaces the server round-18 company-identity fields —
  `corp_name` (Korean, as filed with DART), `corp_name_en`
  (KRX-registered English name), `report_nm` (canonical DART form
  name), `report_nm_en` (English filing-type label, e.g.
  `"Merger Decision"`) — plus the round-17a `source_url` (canonical
  DART viewer link) and `numeric_expectation` (`"HIGH"`/`"LOW"`
  pre-purchase signal). These fields were already on the wire;
  Pydantic's default `extra="ignore"` silently dropped them, so
  upgrading is required to see them.
- `RecentFiling` now carries the same `corp_name_en` /
  `report_nm_en` / `source_url` / `numeric_expectation` fields and
  the round-15b AI-enrichment fields (`importance_score`,
  `event_type`, `sector_tags`, `ticker_tags`, `actionable_for`) that
  appear when a filing's summary is already cached server-side.

### Notes

- All new fields are `Optional` with `None` defaults — the models
  still validate responses from older servers unchanged.

## [0.3.2] and earlier

See the repository commit history; this changelog starts at 0.3.3.
