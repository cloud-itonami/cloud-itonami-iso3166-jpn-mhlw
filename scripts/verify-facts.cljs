;; Re-fetch every entry in facts.edn from the live authority.
;;
;;   nbb --classpath scripts scripts/verify-facts.cljs [facts.edn]
;;
;; -- THREE EXIT CODES, ON PURPOSE
;;
;;   0  every entry checked and every entry agreed with the register
;;   1  the register is wrong about the world -- a page is gone, a law id no
;;      longer resolves, a repeal happened, a form dangles. A claim, from a
;;      run that was able to make claims.
;;   2  REFUSED. This run could not answer. Not a pass.
;;
;; The third code is the point. On this host it is not academic: the
;; www.mhlw.go.jp 404 page carries 労働基準法 and 労働基準監督署 in its global
;; navigation, so a needle that drifts into site chrome starts matching the
;; missing page. That is a broken check, not a changed page. Reported as a
;; failure it would be indistinguishable from a real finding; reported as a
;; pass it would be worse. It REFUSES.
;;
;; -- WHY EACH CHECK IS THE CHECK IT IS
;;
;; Each non-obvious decision is forced by something measured against these
;; hosts on 2026-08-27 and written out in facts.edn's header rather than
;; repeated here. In short:
;;
;;   statutes are identified by total_count and law_id, NEVER by HTTP status,
;;   because the cheap listing endpoint answers a fabricated law id with 200;
;;
;;   the repeal fields are read from revision_info, and their PRESENCE is
;;   asserted separately from their value, because reading them from law_info
;;   yields nil and the live not-repealed token is the string "None" -- a
;;   register that never asked and an authority that said no look identical;
;;
;;   being in force is TWO fields, because repeal_status None does not mean
;;   the served revision is the current one, and remain_in_force is true only
;;   on laws that are already dead;
;;
;;   every page needle is re-subtracted from the LIVE 404 body each run, and a
;;   needle found there refuses rather than passes;
;;
;;   page bodies are decoded with a fatal UTF-8 decoder, because this host
;;   sends a bare text/html and declares its encoding only in the document --
;;   a mojibake needle failure is not a finding;
;;
;;   no check asserts a byte size, because two different documents on this
;;   host are exactly the same length;
;;
;;   forms are checked on status, content-type AND magic bytes, because a
;;   deleted one answers 404 with 48 KB of HTML, and on still being LINKED,
;;   because a file nothing cites any more is an orphaned citation that no
;;   check on the file alone can see.
;;
;; -- SELF-TESTS ASSERT THE REASON, NOT THE VERDICT
;;
;; A negative test that only asserts this-failed counts a failure for the
;; wrong cause as a success. Every self-test below names the reason keyword it
;; expects and the run REFUSES if it gets any other -- including :ok. The
;; judges are pure and take already-fetched data, so the self-tests drive the
;; SAME functions the run uses, mostly over real live responses with one
;; expectation doctored.

(ns verify-facts
  (:require ["fs" :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [host-probe :refer [decode-utf8-strict fetch-bytes fetch-head fetch-json
                                page-title de-tag]]))

;; -- refusal ---------------------------------------------------------------

(def ^:private refuse-reasons
  #{:refused/fetch-error
    :refused/unparseable-json
    :refused/undecodable-body
    :refused/no-title
    :refused/needle-on-404
    :refused/missing-probe-not-404
    :refused/repeal-field-absent
    :refused/revision-field-absent
    :refused/self-test})

(defn- refused? [reason] (contains? refuse-reasons reason))

;; -- judges (pure) ---------------------------------------------------------

(defn- judge-missing-page
  "The 404 probe. If this stops being a 404, every page needle below is being
   subtracted from something that is not a missing page, and the whole page
   section is meaningless. That refuses; it does not fail eight times."
  [entry {:keys [status bytes error]}]
  (cond
    error {:reason :refused/fetch-error :detail error}
    (not= status (:control/expect-status entry))
    {:reason :refused/missing-probe-not-404
     :detail (str "probe answered " status ", expected " (:control/expect-status entry))}
    :else
    (let [text (decode-utf8-strict bytes)]
      (cond
        (nil? text) {:reason :refused/undecodable-body :detail "probe body is not valid UTF-8"}
        :else
        (let [html (decode-utf8-strict bytes)
              title (page-title html)]
          (cond
            (nil? title) {:reason :refused/no-title :detail "probe has no title element"}
            (not= title (:control/expect-title entry))
            {:reason :control/title-mismatch
             :detail (str "probe title " (pr-str title) ", register says "
                          (pr-str (:control/expect-title entry)))}
            :else {:reason :ok :detail (str (count (de-tag html)) " chars of 404 body")}))))))

(defn- judge-page
  "status, exact title, needle present here AND absent from the live 404 body.
   The needle subtraction is redone every run because this host's chrome is
   what makes a needle wrong, and chrome changes without the page changing."
  [entry {:keys [status bytes error]} text-404]
  (cond
    error {:reason :refused/fetch-error :detail error}
    (not= 200 status) {:reason :page/bad-status :detail (str "HTTP " status)}
    :else
    (let [html (decode-utf8-strict bytes)]
      (cond
        (nil? html) {:reason :refused/undecodable-body :detail "body is not valid UTF-8"}
        :else
        (let [title (page-title html)
              text (de-tag html)
              needle (:page/needle entry)]
          (cond
            (nil? title) {:reason :refused/no-title :detail "no title element"}
            ;; The 404 check comes BEFORE the presence check on purpose. A
            ;; needle that has drifted into chrome is present on the page too,
            ;; so testing presence first would report :ok and never look.
            (str/includes? text-404 needle)
            {:reason :refused/needle-on-404
             :detail (str "needle " (pr-str needle)
                          " is on the live 404 body -- it can no longer tell this page"
                          " from a deleted one; choose another by subtraction")}
            (not= title (:page/title entry))
            {:reason :page/title-mismatch
             :detail (str "live " (pr-str title) ", register " (pr-str (:page/title entry)))}
            (not (str/includes? text needle))
            {:reason :page/needle-absent
             :detail (str "needle " (pr-str needle) " not in " (count text) " chars of text")}
            :else {:reason :ok :detail (str (count text) " chars, needle held")}))))))

(def ^:private magic
  {:docx [0x50 0x4B 0x03 0x04]                 ; PK.. -- a ZIP container
   :pdf  [0x25 0x50 0x44 0x46]})               ; %PDF

(def ^:private ctype-prefix
  {:docx "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
   :pdf  "application/pdf"})

(defn- magic-ok? [kind bytes]
  (let [want (magic kind)]
    (and want
         (>= (.-length bytes) (count want))
         (every? true? (map-indexed (fn [i b] (= b (aget bytes i))) want)))))

(defn- judge-form
  "Three independent properties plus still-being-linked. A deleted form on
   this host answers 404 with 48 KB of HTML, so a non-empty body proves
   nothing and the magic bytes are the check that cannot be satisfied by the
   404 page."
  [entry {:keys [status ctype bytes error]} linking-page-html]
  (let [kind (:form/kind entry)]
    (cond
      error {:reason :refused/fetch-error :detail error}
      (not= 200 status) {:reason :form/bad-status :detail (str "HTTP " status)}
      (not (str/starts-with? ctype (ctype-prefix kind)))
      {:reason :form/ctype-mismatch
       :detail (str "content-type " (pr-str ctype) ", expected " (pr-str (ctype-prefix kind)))}
      (not (magic-ok? kind bytes))
      {:reason :form/magic-mismatch
       :detail (str "leading bytes are not " (name kind)
                    " -- a 404 HTML body reaches this line with a plausible size")}
      :else
      ;; The path, not the whole URL: two of these forms are linked with a
      ;; site-relative href and one with an absolute one.
      (let [path (str/replace (:form/url entry) #"^https?://[^/]+" "")]
        (if (and linking-page-html (not (str/includes? linking-page-html path)))
          {:reason :form/orphaned-citation
           :detail (str (:form/linked-from entry) " no longer links " path
                        " -- the file is alive but nothing cites it")}
          {:reason :ok :detail (str (.-length bytes) " bytes, magic and link held")})))))

(defn- judge-missing-form
  [entry {:keys [status ctype error]}]
  (cond
    error {:reason :refused/fetch-error :detail error}
    (not= status (:control/expect-status entry))
    {:reason :control/status-mismatch
     :detail (str "missing form answered " status ", expected "
                  (:control/expect-status entry)
                  " -- the form checks' reason for reading magic bytes has changed")}
    (not (str/starts-with? ctype (:control/expect-ctype-prefix entry)))
    {:reason :control/ctype-mismatch :detail (str "content-type " (pr-str ctype))}
    :else {:reason :ok :detail (str "404 still answers " ctype)}))

(defn- law-row
  "The single result, or nil. Never looks at the HTTP status."
  [json]
  (when (map? json)
    (first (get json "laws"))))

(defn- judge-statute
  "Identity by total_count and law_id; in-force by two independent fields
   whose PRESENCE is asserted before their value.

   Works for live statutes and for controls alike -- the judge compares
   against whatever the register records for that entry, so a control that
   records Repeal is held to Repeal. That is what lets the self-tests feed it
   a real repealed law with a doctored expectation and get a precise reason."
  [entry {:keys [json bad-json error]}]
  (cond
    error {:reason :refused/fetch-error :detail error}
    bad-json {:reason :refused/unparseable-json :detail "response was not JSON"}
    :else
    (let [tc (get json "total_count")
          row (law-row json)]
      (cond
        (not= 1 tc)
        {:reason :law/not-found
         :detail (str "total_count " tc " for law_id " (:law/id entry)
                      " -- note this response was HTTP 200")}
        (nil? row) {:reason :refused/unparseable-json :detail "total_count 1 but no row"}
        :else
        (let [info (get row "law_info")
              rev (get row "revision_info")]
          (cond
            (not (map? rev))
            {:reason :refused/revision-field-absent :detail "no revision_info object"}

            ;; Presence before value. nil and "None" mean opposite things.
            (not (contains? rev "repeal_status"))
            {:reason :refused/repeal-field-absent
             :detail "revision_info has no repeal_status -- cannot conclude not-repealed from a field that is not there"}

            (not (contains? rev "current_revision_status"))
            {:reason :refused/revision-field-absent
             :detail "revision_info has no current_revision_status"}

            ;; If the authority ever moves these into law_info, the register's
            ;; header is stale. Say so rather than quietly reading whichever
            ;; one happens to answer.
            (contains? info "repeal_status")
            {:reason :law/repeal-field-moved
             :detail "law_info now carries repeal_status too -- facts.edn's :host/repeal-fields-in is stale"}

            (not= (:law/id entry) (get info "law_id"))
            {:reason :law/id-mismatch
             :detail (str "asked " (:law/id entry) ", got " (get info "law_id"))}

            (not= (:law/title entry) (get rev "law_title"))
            {:reason :law/title-mismatch
             :detail (str "live " (pr-str (get rev "law_title"))
                          ", register " (pr-str (:law/title entry)))}

            (not= (:law/num entry) (get info "law_num"))
            {:reason :law/num-mismatch
             :detail (str "live " (pr-str (get info "law_num")))}

            (not= (:law/type entry) (get info "law_type"))
            {:reason :law/type-mismatch
             :detail (str "live " (pr-str (get info "law_type")))}

            (not= (:law/promulgated entry) (get info "promulgation_date"))
            {:reason :law/promulgation-mismatch
             :detail (str "live " (pr-str (get info "promulgation_date")))}

            (not= (:law/repeal-status entry) (get rev "repeal_status"))
            {:reason :law/repeal-mismatch
             :detail (str "live repeal_status " (pr-str (get rev "repeal_status"))
                          ", register " (pr-str (:law/repeal-status entry)))}

            (not= (:law/revision-status entry) (get rev "current_revision_status"))
            {:reason :law/revision-mismatch
             :detail (str "live current_revision_status "
                          (pr-str (get rev "current_revision_status"))
                          ", register " (pr-str (:law/revision-status entry))
                          " -- this field is independent of repeal_status")}

            (and (contains? entry :law/repeal-date)
                 (not= (:law/repeal-date entry) (get rev "repeal_date")))
            {:reason :law/repeal-date-mismatch
             :detail (str "live " (pr-str (get rev "repeal_date")))}

            (and (contains? entry :law/remain-in-force)
                 (not= (:law/remain-in-force entry) (get rev "remain_in_force")))
            {:reason :law/remain-in-force-mismatch
             :detail (str "live remain_in_force " (pr-str (get rev "remain_in_force")))}

            :else
            {:reason :ok
             :detail (str (get rev "law_title") " " (get rev "repeal_status")
                          "/" (get rev "current_revision_status"))}))))))

(defn- judge-fabricated-id
  "The trap that justifies never reading this endpoint's status. If it ever
   starts 404ing, the register's header is stale and this fails loudly rather
   than becoming silently over-cautious."
  [entry {:keys [status json bad-json error]}]
  (cond
    error {:reason :refused/fetch-error :detail error}
    bad-json {:reason :refused/unparseable-json :detail "response was not JSON"}
    (not= (:control/expect-status entry) status)
    {:reason :control/status-mismatch
     :detail (str "fabricated law id answered " status ", register says "
                  (:control/expect-status entry)
                  " -- if this endpoint now discriminates by status, facts.edn's"
                  " header is out of date")}
    (not= (:control/expect-total-count entry) (get json "total_count"))
    {:reason :control/total-count-mismatch
     :detail (str "total_count " (get json "total_count")
                  " for a fabricated id -- something now resolves it")}
    :else {:reason :ok :detail "fabricated id still answers 200 with total_count 0"}))

(defn- judge-page-host
  "The page host's declared behaviour, re-measured. Without this the
   :host/mhlw entry would be prose: it would name a :source/verify tag that no
   check implements, and read as verified because every OTHER entry passed."
  [entry {:keys [head-404 head-200 text-404 text-front bytes-404 bytes-200]}]
  (cond
    (:error head-404) {:reason :refused/fetch-error :detail (:error head-404)}
    (:error head-200) {:reason :refused/fetch-error :detail (:error head-200)}

    ;; The 404 claims an empty body and then serves one. Both halves are
    ;; asserted, and the second against the GET length rather than a pinned
    ;; number -- the claim is the contradiction, not today's page size.
    (not= (:host/head-404-content-length entry) (:content-length head-404))
    {:reason :host/head-404-length-changed
     :detail (str "HEAD on the 404 reports content-length "
                  (pr-str (:content-length head-404)) ", register says "
                  (pr-str (:host/head-404-content-length entry)))}

    (not (pos? bytes-404))
    {:reason :host/head-404-no-longer-lies
     :detail (str "HEAD says " (pr-str (:content-length head-404))
                  " and GET returns " bytes-404
                  " bytes -- the contradiction the register documents is gone")}

    ;; A live page's HEAD length is never the body's: absent under identity
    ;; encoding, the compressed size under gzip. Compared, not pinned, because
    ;; which of those two you see depends on the client -- and this register
    ;; recorded the client's behaviour as the host's once already.
    (not= (:host/head-200-reports-body-size? entry)
          (= (:content-length head-200) (str bytes-200)))
    {:reason :host/head-200-length-changed
     :detail (str "HEAD on a live page reports " (pr-str (:content-length head-200))
                  " and GET returns " bytes-200 " bytes -- register says"
                  " :host/head-200-reports-body-size? "
                  (:host/head-200-reports-body-size? entry))}

    ;; :host/missing-longer-than-front? -- false here, unlike the sibling FSA
    ;; host. Held live so the register cannot inherit the wrong host's note.
    (not= (:host/missing-longer-than-front? entry) (> (count text-404) (count text-front)))
    {:reason :host/missing-front-relation-changed
     :detail (str "404 text " (count text-404) " chars, front " (count text-front)
                  " -- facts.edn says :host/missing-longer-than-front? "
                  (:host/missing-longer-than-front? entry))}

    :else
    {:reason :ok
     :detail (str "404 text " (count text-404) " chars vs front " (count text-front)
                  "; HEAD 404 says 0 for " bytes-404 " bytes, HEAD 200 says "
                  (pr-str (:content-length head-200)) " for " bytes-200)}))

(defn- judge-statute-host
  "The statute host's corpus size, as a FLOOR rather than an equality.

   Laws are added and repealed ones stay in the corpus as repealed -- 414 of
   them do -- so the count only grows in normal operation and pinning it would
   schedule a false failure. A count that has SHRUNK is worth stopping for: it
   means the listing is returning a subset, and every count in the register's
   header was taken over the whole of it."
  [entry {:keys [json bad-json error]}]
  (cond
    error {:reason :refused/fetch-error :detail error}
    bad-json {:reason :refused/unparseable-json :detail "response was not JSON"}
    :else
    (let [live (get json "total_count")
          recorded (:host/corpus-size entry)]
      (cond
        (not (number? live))
        {:reason :refused/unparseable-json :detail "listing returned no total_count"}
        (< live recorded)
        {:reason :host/corpus-shrank
         :detail (str "corpus is " live ", was " recorded " when the header's token counts"
                      " were taken -- those counts were over a larger set than exists now")}
        :else
        {:reason :ok
         :detail (str "corpus " live " (floor " recorded ", +" (- live recorded) ")")}))))

(defn- judge-token-coverage
  "The repeal tokens the host entry claims must be exactly the ones the
   controls hold live. Without this, dropping a control would silently shrink
   what the repeal check can see while every remaining entry still passed."
  [host-entry controls]
  (let [claimed (set (:host/repeal-tokens host-entry))
        held (set (keep :law/repeal-status controls))
        held (disj held "None")
        missing (remove held claimed)
        extra (remove claimed held)]
    (cond
      (seq missing)
      {:reason :host/token-uncontrolled
       :detail (str "repeal tokens with no control: " (pr-str (vec missing)))}
      (seq extra)
      {:reason :host/token-undeclared
       :detail (str "controls hold tokens the host entry does not declare: " (pr-str (vec extra)))}
      :else
      {:reason :ok
       :detail (str (count claimed) " repeal tokens controlled; revision tokens uncontrolled by design: "
                    (pr-str (vec (:host/uncontrolled-revision-tokens host-entry))))})))

;; -- self-tests ------------------------------------------------------------
;;
;; Each names the reason it expects. Anything else -- including :ok -- refuses
;; the whole run. Most are fed REAL live responses with one expectation
;; doctored, so they exercise the judge on real bytes rather than on a fixture
;; that agrees with it by construction.

(defn- self-tests
  [{:keys [repealed-json prev-enforced-json live-json fabricated-json
           page page-bytes text-404 form form-bytes missing-form host-observed]} entries]
  (let [by-id (into {} (map (juxt :source/id identity)) entries)
        lsa (by-id :law/labour-standards-act)
        repealed (by-id :law/repealed-control)
        prev (by-id :law/previous-enforced-control)
        t (fn [name expected actual]
            {:name name :expected expected :actual (:reason actual) :detail (:detail actual)})
        ;; A self-test needs a HEALTHY sample to doctor. Sampling a fixed entry
        ;; by name means that when THAT entry is the one the register has got
        ;; wrong, the self-tests fail and the run refuses -- burying the real
        ;; finding under a claim that the instruments are broken. They are not;
        ;; the register is, which is what the run is supposed to report. So the
        ;; caller passes whichever page and form actually came back healthy,
        ;; and if none did, the affected tests are SKIPPED and say so rather
        ;; than passing or failing.
        skip (fn [name expected why]
               {:name name :expected expected :actual :skipped :skipped? true :detail why})]
    (concat
     [;; -- the repeal check is a check, not a constant.
     ;; Real live 簡易生命保険法, judged against an entry that expects it to be
     ;; live. Only the repeal expectation differs, so the reason must be the
     ;; repeal one and nothing else.
     (t "repealed law judged as live -> repeal-mismatch"
        :law/repeal-mismatch
        (judge-statute (assoc repealed :law/repeal-status "None" :law/revision-status "CurrentEnforced")
                       repealed-json))

     ;; -- and the revision check is independent of it. Real 厚生年金保険法,
     ;; which is NOT repealed, so a repeal-only check passes it. The reason
     ;; must be the revision one.
     (t "not-repealed but superseded -> revision-mismatch"
        :law/revision-mismatch
        (judge-statute (assoc prev :law/revision-status "CurrentEnforced") prev-enforced-json))

     ;; -- the opposite direction, so neither of the two above is satisfied by
     ;; a judge that always complains.
     (t "control judged against its own recorded state -> ok"
        :ok
        (judge-statute repealed repealed-json))

     ;; -- presence is asserted before value: a revision_info with the field
     ;; deleted must refuse, NOT read as not-repealed. This is the mistake the
     ;; register's header describes, reproduced exactly.
     (t "repeal_status absent -> refuses, does not read as not-repealed"
        :refused/repeal-field-absent
        (judge-statute lsa
                       (update-in live-json [:json "laws" 0 "revision_info"]
                                  dissoc "repeal_status")))

     ;; -- and if the authority moved the field to law_info, say so rather
     ;; than reading whichever object answers.
     (t "repeal_status appearing in law_info -> field-moved"
        :law/repeal-field-moved
        (judge-statute lsa
                       (assoc-in live-json [:json "laws" 0 "law_info" "repeal_status"] "None")))

     ;; -- identity does not come from HTTP status. total_count 0 arrives with
     ;; a 200 and must still be a miss.
     (t "fabricated id judged as a statute -> not-found despite HTTP 200"
        :law/not-found
        (judge-statute (assoc lsa :law/id "999AC0000000999") fabricated-json))

     ;; -- the identity fields are compared, not decorative.
     (t "wrong expected title -> title-mismatch"
        :law/title-mismatch
        (judge-statute (assoc lsa :law/title "存在しない法律") live-json))

     ;; -- the 404 body itself, offered as a form. Needs no healthy sample.
     (t "404 HTML body offered as a docx -> bad-status"
        :form/bad-status
        (judge-form (or form {:form/kind :docx}) missing-form nil))

     ;; -- the page host's declared behaviour is a claim, not prose. If HEAD
     ;; became useful, the register's reason for never using it is stale.
     ;; If HEAD on a live page ever did report the real body size, the
     ;; register's reason for always GETting would be stale.
     (t "HEAD on a live page reporting the true body size -> head-200-length-changed"
        :host/head-200-length-changed
        (judge-page-host (by-id :host/mhlw)
                         (assoc host-observed
                                :head-200 {:status 200
                                           :content-length (str (:bytes-200 host-observed))})))

     ;; And if the 404 stopped claiming to be empty.
     (t "404 HEAD reporting a real length -> head-404-length-changed"
        :host/head-404-length-changed
        (judge-page-host (by-id :host/mhlw)
                         (assoc host-observed
                                :head-404 {:status 404 :content-length "48546"})))

     ;; -- and the opposite direction, over the real HEAD responses.
     (t "page host judged as recorded -> ok"
        :ok
        (judge-page-host (by-id :host/mhlw) host-observed))

     ;; -- the corpus floor. A shrunken corpus invalidates every token count in
     ;; the header, which were taken over the whole of it.
     (t "corpus smaller than when the header was written -> corpus-shrank"
        :host/corpus-shrank
        (judge-statute-host (by-id :host/e-gov) {:json {"total_count" 10}}))

     ;; -- but growth is normal and must not fail.
     (t "corpus larger than recorded -> ok"
        :ok
        (judge-statute-host (by-id :host/e-gov) {:json {"total_count" 999999}}))

     ;; -- dropping a control must be visible.
     (t "a repeal token with no control -> token-uncontrolled"
        :host/token-uncontrolled
        (judge-token-coverage (by-id :host/e-gov)
                              (remove #(= "Expire" (:law/repeal-status %))
                                      (filter :law/repeal-status (vals by-id)))))]

     ;; -- the needle subtraction, over whichever page came back healthy.
     ;; 労働基準法 IS on this host's 404 body, so a page checked with it must
     ;; REFUSE, not pass and not fail.
     (if-not page
       [(skip "needle that is on the 404 -> refuses" :refused/needle-on-404
              "no page in the register is currently healthy to doctor")
        (skip "needle on neither page nor 404 -> ordinary needle-absent" :page/needle-absent
              "no page in the register is currently healthy to doctor")
        (skip "page judged as recorded -> ok" :ok
              "no page in the register is currently healthy to doctor")]
       [(t "needle that is on the 404 -> refuses"
           :refused/needle-on-404
           (judge-page (assoc page :page/needle "労働基準法") page-bytes text-404))

        ;; -- a needle on neither is an ordinary failure, so the refusal above
        ;; is specific to chrome drift rather than to any absent needle.
        (t "needle on neither page nor 404 -> ordinary needle-absent"
           :page/needle-absent
           (judge-page (assoc page :page/needle "この文字列はどこにもない") page-bytes text-404))

        ;; -- and the page passes as recorded, so the two above are not a judge
        ;; that always complains.
        (t "page judged as recorded -> ok" :ok (judge-page page page-bytes text-404))])

     (if-not form
       [(skip "docx judged as pdf -> magic-mismatch" :form/magic-mismatch
              "no form in the register is currently healthy to doctor")
        (skip "form no longer linked from its page -> orphaned-citation" :form/orphaned-citation
              "no form in the register is currently healthy to doctor")
        (skip "form judged with a page that does link it -> ok" :ok
              "no form in the register is currently healthy to doctor")]
       ;; -- magic bytes, not body size. The 404 HTML body is 48 KB and would
       ;; satisfy any is-it-substantial test.
       [(t "docx judged as pdf -> magic-mismatch"
           :form/magic-mismatch
           (judge-form (assoc form :form/kind :pdf)
                       (assoc form-bytes :ctype "application/pdf") nil))

        ;; -- a live file that nothing links to any more.
        (t "form no longer linked from its page -> orphaned-citation"
           :form/orphaned-citation
           (judge-form form form-bytes "<html>a page that does not mention it</html>"))

        ;; -- and the form passes when linked, so the two above discriminate.
        (t "form judged with a page that does link it -> ok"
           :ok
           (judge-form form form-bytes (str "<a href=\"" (:form/url form) "\">x</a>")))]))))

;; -- main ------------------------------------------------------------------

(defn- of-kind [entries k] (filter #(= k (:source/verify %)) entries))

(def ^:private facts-path
  "facts.edn by default. scripts/break-tests.cljs points this at a doctored
   copy to check that a broken register actually changes this script's EXIT
   CODE -- the self-tests above exercise the judges, but nothing in them can
   tell you the aggregation and the three exit codes are wired up."
  (or (first (remove #(str/starts-with? % "--") *command-line-args*))
      "facts.edn"))

(defn- main []
  (let [entries (edn/read-string (fs/readFileSync facts-path "utf8"))
        by-id (into {} (map (juxt :source/id identity)) entries)
        api-base (:host/api-base (by-id :host/e-gov))
        missing-page (by-id :control/missing-page)
        missing-form (by-id :control/missing-form)
        statutes (of-kind entries :verify/statute)
        controls (concat (of-kind entries :verify/control-repealed)
                         (of-kind entries :verify/control-previous-enforced))
        fabricated (first (of-kind entries :verify/fabricated-id))
        pages (of-kind entries :verify/page)
        forms (of-kind entries :verify/form)
        law-url (fn [e] (str api-base (:law/id e)))]
    (-> (fetch-bytes (:control/url missing-page))
        (.then
         (fn [probe]
           (let [probe-verdict (judge-missing-page missing-page probe)]
             (if (refused? (:reason probe-verdict))
               (do (println "  REFUSED " :control/missing-page "  "
                            (:reason probe-verdict) "  " (:detail probe-verdict))
                   (println "REFUSED -- the 404 probe could not be established, so no page needle"
                            " could be subtracted from anything. Not a pass.")
                   (js/process.exit 2))
               (let [text-404 (de-tag (decode-utf8-strict (:bytes probe)))]
                 (-> (js/Promise.all
                      (clj->js
                       [(js/Promise.all (clj->js (map #(fetch-json (law-url %)) statutes)))
                        (js/Promise.all (clj->js (map #(fetch-json (law-url %)) controls)))
                        (fetch-json (law-url fabricated))
                        (js/Promise.all (clj->js (map #(fetch-bytes (:page/url %)) pages)))
                        (js/Promise.all (clj->js (map #(fetch-bytes (:form/url %)) forms)))
                        (fetch-bytes (:control/url missing-form))
                        ;; the pages the forms claim to be linked from
                        (js/Promise.all (clj->js (map #(fetch-bytes (:form/linked-from %)) forms)))
                        ;; the page host's own declared behaviour
                        (fetch-bytes (:host/front-page (by-id :host/mhlw)))
                        (fetch-head (:control/url missing-page))
                        (fetch-head (:page/url (first pages)))
                        (fetch-json (str (str/replace api-base #"\?.*$" "") "?limit=1"))]))
                     (.then
                      (fn [[statute-rs control-rs fab-r page-rs form-rs missing-form-r linking-rs
                            front-r head-404 head-200 corpus-r]]
                        (let [statute-rs (js->clj statute-rs)
                              control-rs (js->clj control-rs)
                              page-rs (js->clj page-rs)
                              form-rs (js->clj form-rs)
                              linking-rs (js->clj linking-rs)
                              linking-html (map #(when (and (nil? (:error %)) (= 200 (:status %)))
                                                   (decode-utf8-strict (:bytes %)))
                                                linking-rs)
                              host-obs {:head-404 (js->clj head-404 :keywordize-keys true)
                                        :head-200 (js->clj head-200 :keywordize-keys true)
                                        :text-404 text-404
                                        :bytes-404 (.-length (:bytes probe))
                                        :bytes-200 (.-length (:bytes (first page-rs)))
                                        :text-front (or (some-> (:bytes (js->clj front-r))
                                                                decode-utf8-strict de-tag)
                                                        "")}
                              results
                              (concat
                               [(assoc probe-verdict :id :control/missing-page)]
                               (map (fn [e r] (assoc (judge-statute e r) :id (:source/id e)))
                                    statutes statute-rs)
                               (map (fn [e r] (assoc (judge-statute e r) :id (:source/id e)))
                                    controls control-rs)
                               [(assoc (judge-fabricated-id fabricated fab-r)
                                       :id (:source/id fabricated))]
                               (map (fn [e r] (assoc (judge-page e r text-404) :id (:source/id e)))
                                    pages page-rs)
                               (map (fn [e r h] (assoc (judge-form e r h) :id (:source/id e)))
                                    forms form-rs linking-html)
                               [(assoc (judge-missing-form missing-form missing-form-r)
                                       :id (:source/id missing-form))]
                               [(assoc (judge-page-host (by-id :host/mhlw) host-obs)
                                       :id :host/mhlw)]
                               [(assoc (judge-statute-host (by-id :host/e-gov) corpus-r)
                                       :id :host/e-gov)]
                               [(assoc (judge-token-coverage (by-id :host/e-gov) controls)
                                       :id :host/e-gov-tokens)])

                              ;; Self-tests run over the live responses just
                              ;; fetched, with one expectation doctored each.
                              pick (fn [es rs id]
                                     (some (fn [[e r]] (when (= id (:source/id e)) r))
                                           (map vector es rs)))
                              ;; The first page and form that came back
                              ;; healthy, for the self-tests to doctor. See the
                              ;; comment in self-tests for why this is not a
                              ;; fixed entry chosen by name.
                              healthy-page (first (keep (fn [[e r]]
                                                          (when (= :ok (:reason (judge-page e r text-404)))
                                                            {:entry e :resp r}))
                                                        (map vector pages page-rs)))
                              healthy-form (first (keep (fn [[e r h]]
                                                          (when (= :ok (:reason (judge-form e r h)))
                                                            {:entry e :resp r}))
                                                        (map vector forms form-rs linking-html)))
                              st (self-tests
                                  {:repealed-json (pick controls control-rs :law/repealed-control)
                                   :prev-enforced-json (pick controls control-rs
                                                             :law/previous-enforced-control)
                                   :live-json (pick statutes statute-rs :law/labour-standards-act)
                                   :fabricated-json fab-r
                                   :page (:entry healthy-page)
                                   :page-bytes (:resp healthy-page)
                                   :text-404 text-404
                                   :form (:entry healthy-form)
                                   :form-bytes (:resp healthy-form)
                                   :missing-form missing-form-r
                                   :host-observed host-obs}
                                  entries)
                              st (vec st)
                              st-skipped (filter :skipped? st)
                              st-bad (remove #(or (:skipped? %) (= (:expected %) (:actual %))) st)]

                          (doseq [r results]
                            (println (str (cond (= :ok (:reason r)) "  ok      "
                                                (refused? (:reason r)) "  REFUSED "
                                                :else "  FAIL    ")
                                          (:id r) "  " (:reason r) "  " (:detail r))))
                          (println)
                          (doseq [s st]
                            (println (str (cond (:skipped? s) "  self SKIP "
                                                (= (:expected s) (:actual s)) "  self ok   "
                                                :else "  self BAD  ")
                                          (:name s)
                                          (cond (:skipped? s) (str "  -- " (:detail s))
                                                (not= (:expected s) (:actual s))
                                                (str "  expected " (:expected s) " got " (:actual s))
                                                :else ""))))
                          (println)
                          (println (str "checked " (count results) " entries: "
                                        (count statutes) " statutes, " (count controls) " law controls, "
                                        "1 fabricated-id control, " (count pages) " pages, "
                                        (count forms) " forms, 2 missing-resource controls, "
                                        "2 host checks, 1 token-coverage check; "
                                        (count st) " self-tests"
                                        (when (seq st-skipped)
                                          (str " (" (count st-skipped) " SKIPPED -- no healthy"
                                               " sample to doctor; those judges are unattested"
                                               " this run)"))))

                          (let [refused (filter #(refused? (:reason %)) results)
                                failed (filter #(and (not= :ok (:reason %))
                                                     (not (refused? (:reason %)))) results)]
                            (cond
                              (seq st-bad)
                              (do (println (str "REFUSED -- " (count st-bad)
                                                " self-test(s) returned a reason other than the one"
                                                " they name. The judges are not behaving as documented,"
                                                " so this run's passes mean nothing."))
                                  (js/process.exit 2))

                              (seq refused)
                              (do (println (str "REFUSED -- " (count refused)
                                                " entry/entries could not be answered"))
                                  (js/process.exit 2))

                              (seq failed)
                              (do (println (str "FAIL -- " (count failed)
                                                " entry/entries disagree with the register"))
                                  (js/process.exit 1))

                              :else
                              (println (str "OK -- all " (count results)
                                            " entries agree with the live authorities"))))))))))))))))

(main)
