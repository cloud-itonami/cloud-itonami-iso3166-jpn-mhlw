# Business Model: Independent MHLW-Regulated Labor-Standards Compliance Service — Japan (MHLW)

## Classification

- Repository: `cloud-itonami-iso3166-jpn-mhlw`
- ISO 3166 (agency-level): `JPN-MHLW`, parent `JPN`
- Ooyake cross-reference: `gov.jpn.mhlw` (Ministry of Health, Labour and Welfare / 厚生労働省)
- Activity: compliance with the Labor Standards Act (労働基準法) for an operator hiring staff in Japan to fulfil a public-sector contract, including 36-agreement (36協定) overtime-limit filings and workplace safety reporting obligations under MHLW's Labour Standards Inspection Office (労働基準監督署)
- Social impact: [:worker-protection-clarity :labor-standards-compliance :public-spend-transparency]

## Customer

- an operator hiring local staff in Japan to deliver a public-sector contract
- an operator needing a 36-agreement (36協定) filed before requiring overtime work
- a foreign employer confirming Japanese labor-standards obligations for the first time

## Offer

- Labor Standards Act (労働基準法) compliance checklist for the operator's specific staffing plan
- 36-agreement (36協定) filing walkthrough
- workplace safety and reporting-obligation checklist
- compliance-audit export package for the operator's own records

## Revenue

- per-engagement compliance-review fee
- recurring regulatory-change monitoring subscription
- compliance-audit export package

## Trust Controls

- any actual filing, registration, or compliance-program submission
  requires Labor-Standards Compliance Governor clearance and always escalates to human
  sign-off (`:filing/submit` is never automated at any phase)
- a false or fabricated regulatory-requirement claim is a HARD hold that
  cannot be overridden by human approval alone — it must be corrected
  against a cited MHLW source first
- this service does **not** provide legal or tax advice; characterization
  and filing on the client's behalf beyond checklist/draft assistance
  routes to Japan-licensed counsel or a registered agent
- every requirement cites the official MHLW source or
  regulation, never invented

## Boundary with adjacent actors (read before forking)

- **`cloud-itonami-iso3166-jpn`**: the COUNTRY-level coordinator (general
  Japan public-sector market entry). This repo is a narrower, deeper
  AGENCY-level leaf — most operators need the country-level blueprint plus
  only the agency-level blueprints that actually apply to their contract.
- **`com-etzhayyim-ooyake`** (etzhayyim/root): read-only civic-wayfinding
  mirror of government structure, non-commercial, barred from acting as or
  for the government (G3 impersonation ban). This blueprint is commercial
  and never claims to be Ministry of Health, Labour and Welfare or an official channel.
- **`matsurigoto`** (etzhayyim/root): sovereign e-government statecraft —
  literally the government. This blueprint is an independent operator that
  engages with MHLW under its public rules — never the
  agency itself.
- **`com-etzhayyim-toritsugi`** (etzhayyim/root): guides a consenting
  INDIVIDUAL citizen through their OWN procedure, non-profit,
  donation-only. This blueprint's client is a business operator, not an
  individual citizen, and it is commercial.
- **`cloud-itonami-M6910`**: helps a client BECOME a legal entity
  (incorporation, ISIC 6910) — a prior, different regulatory phase (company
  law). This blueprint assumes incorporation is already done and handles
  MHLW-specific compliance (a different regulatory domain).
