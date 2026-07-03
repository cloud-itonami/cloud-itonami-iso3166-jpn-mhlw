# Governance

`cloud-itonami-iso3166-jpn-mhlw` is an OSS open-business blueprint. Governance covers both code and
the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- the advisor cannot directly submit a filing/registration or commit a
  public record.
- the Labor-Standards Compliance Governor remains independent of the advisor.
- a fabricated or stale regulatory-requirement claim cannot be overridden
  by human approval alone.
- every commit, hold and approval path is auditable.
- real client/compliance data stays outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or
license should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is
a separate trust mark and should require security, audit, support and
data-flow review, INCLUDING proof of a working referral relationship with
Japan-licensed counsel or a registered agent for whatever licensed
representation Japanese law requires for MHLW filings.

Certified operators can lose certification for:

- bypassing governor checks
- mishandling client or compliance data
- misrepresenting certification status
- failing to respond to security incidents
- hiding material changes to customer-facing operation
- presenting an uncited claim as a legal/tax conclusion
