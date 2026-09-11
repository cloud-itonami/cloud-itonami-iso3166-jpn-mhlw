# cloud-itonami-iso3166-jpn-mhlw

Open ISO 3166 Agency Blueprint for **JPN-MHLW**: Ministry of Health, Labour and Welfare
(厚生労働省, MHLW) — a Japan-agency-level LEAF under
the `cloud-itonami-iso3166-jpn` country-level coordinator.

This repository designs a forkable OSS business for an independent
compliance consultant: an already-incorporated operator (typically one
already using `cloud-itonami-iso3166-jpn` for general Japan market entry)
gets a Compliance Advisor + independent **Labor-Standards Compliance Governor** to
navigate compliance with the Labor Standards Act (労働基準法) for an operator hiring staff in Japan to fulfil a public-sector contract, including 36-agreement (36協定) overtime-limit filings and workplace safety reporting obligations under MHLW's Labour Standards Inspection Office (労働基準監督署).

## No robotics premise — digital/data service exemption

Agency-specific compliance navigation is a pure data/software service with
no physical-domain work — the same exemption class as `cloud-itonami-6310`
and `cloud-itonami-gtin-*`. `blueprint.edn` sets
`:itonami.blueprint/robotics false` and `:required-technologies` lists only
real capabilities (`:identity`, `:forms`, `:dmn`, `:bpmn`, `:audit-ledger`),
no `:robotics`.

## Core Contract

```text
operator intake + prior filing/compliance history
        |
        v
Compliance Advisor -> Labor-Standards Compliance Governor -> compliance draft, or human sign-off
        |
        v
gated filing / registration / compliance-program submission + audit ledger
```

No automated proposal can submit a filing or registration the governor
refuses, suppress a compliance record, or claim a legal conclusion the
governor has not cleared. `:filing/submit` is never in any phase's `:auto`
set — it always requires human sign-off (mirrors `cloud-itonami-M6910`'s
`filing-submit-never-auto-at-any-phase` invariant).

## What this is NOT

- **Not Ministry of Health, Labour and Welfare (厚生労働省) itself, and not the
  government of Japan.** See [`docs/business-model.md`](docs/business-model.md)
  for the boundary with `com-etzhayyim-ooyake`, `matsurigoto`,
  `com-etzhayyim-toritsugi`, `legal-entity.etzhayyim.com`,
  `cloud-itonami-M6910`, and the country-level `cloud-itonami-iso3166-jpn`.
- **Not legal or tax advice.** Every regulatory claim must cite the
  official MHLW source and route final filings to
  Japan-licensed counsel or a registered agent where the law requires
  licensed representation.

## The source register — [`facts.edn`](facts.edn)

The rule above requires a citation against a set. `facts.edn` is that set:
13 statutes and ordinances, 8 MHLW pages, the 4 36協定 filing forms
(様式第9号 and its variants), and 8 controls. **A regulation not in that
table has no spec-basis here** — extend the table, never invent a law id or
a URL.

It is tx-data, so it loads like every other EDN corpus in this workspace:

```clojure
(d/transact conn (edn/read-string (slurp "facts.edn")))
```

Every entry is re-fetched from the live authority by:

```bash
nbb --classpath scripts scripts/verify-facts.cljk     # 0 ok / 1 wrong / 2 REFUSED
nbb --classpath scripts scripts/break-tests.cljk      # does that script actually go red?
nbb --classpath scripts scripts/measure-host.cljk     # regenerate the header's numbers
```

**Exit 2 is not a pass.** A run that could not answer — an unreadable body, a
404 probe that stopped 404ing, a needle that has drifted into site chrome —
reports differently from a run that checked everything and found a problem,
because collapsing those two is how a check quietly stops being one.

Three things this host does that the checks are shaped around, all measured
and written up in `facts.edn`'s header:

- **労働基準法 and 労働基準監督署 are on the 404 page.** The two most obvious
  needles for this repository verify against a page that does not exist, so
  needles are chosen by subtracting the live 404 body and the verifier redoes
  that subtraction every run.
- **A fabricated law id answers HTTP 200.** The statute checks never read the
  status; identity is `total_count` plus an exact `law_id` match.
- **The repeal fields are in `revision_info`, not `law_info`.** Read from the
  wrong object they come back `nil`, and the live not-repealed value is the
  string `"None"` — so their *presence* is asserted separately from their
  value, and a check that cannot find them refuses instead of concluding the
  law is fine.

The controls (`:law/repealed-control` and friends) are cited for **no
proposition**. Deleting one weakens no citation; it turns the check that
depended on it into something that cannot fail.

## Capability layer

Resolves via [`kotoba-lang/iso3166`](https://github.com/kotoba-lang/iso3166)
(code `JPN-MHLW`, `:parent "JPN"`, cross-referenced to ooyake's
`gov.jpn.mhlw`). Required capabilities:

- :identity
- :forms
- :dmn
- :bpmn
- :audit-ledger

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md).

## License

AGPL-3.0-or-later.
