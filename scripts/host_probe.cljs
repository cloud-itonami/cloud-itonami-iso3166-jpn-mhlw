;; The measuring instruments, in ONE place.
;;
;; facts.edn's header states character counts, and verify-facts.cljs subtracts
;; needles from a body it measures the same way. If those two used different
;; text extractors, the register and its verifier would disagree about their
;; own evidence -- and the disagreement would be invisible, because both would
;; look internally consistent.
;;
;; That is not hypothetical here. The first draft of facts.edn was measured
;; with a throwaway script and the verifier was written afterwards; the two
;; de-tag implementations differed only in how they collapse whitespace around
;; removed elements, and the 404 body came out 3576 characters one way and
;; 4389 the other. Same page, same day, same fetch. The numbers in the
;; register were wrong by 23% and nothing in either file could have said so.
;;
;; So there is one de-tag, here, and scripts/measure-host.cljs regenerates
;; every number the register's header states by calling it. If you change
;; de-tag, the header's numbers change with it -- rerun measure-host and
;; update them.

(ns host-probe
  (:require [clojure.string :as str]))

(def ua "cloud-itonami-iso3166-jpn-mhlw facts verifier")

(defn decode-utf8-strict
  "nil when the bytes are not valid UTF-8. Callers must treat nil as REFUSED,
   never as an empty page: the difference between a dead citation and an
   unreadable one is the whole reason exit 2 exists.

   www.mhlw.go.jp sends a bare text/html with no charset and declares UTF-8
   only inside the document, so a decoder that substitutes replacement
   characters would turn an encoding change into a missing needle -- a finding
   about the wrong thing."
  [bytes]
  (try (.decode (js/TextDecoder. "utf-8" #js {:fatal true}) bytes)
       (catch :default _ nil)))

(defn fetch-bytes
  "Bytes, not text. Decoding is a decision each check makes for itself: the
   filing forms are not text at all, and getting that wrong is silent."
  [url]
  (-> (js/fetch url #js {:redirect "follow" :headers #js {"User-Agent" ua}})
      (.then (fn [r]
               (.then (.arrayBuffer r)
                      (fn [ab] {:status (.-status r)
                                :ctype (or (.get (.-headers r) "content-type") "")
                                :bytes (js/Uint8Array. ab)}))))
      (.catch (fn [e] {:error (str e)}))))

(defn fetch-head
  "HEAD, for the one entry that asserts HEAD is useless on this host. Nothing
   else uses it: HEAD reports Content-Length 0 for the 404 body and no
   Content-Length at all for live pages, so it cannot tell them apart."
  [url]
  (-> (js/fetch url #js {:method "HEAD" :redirect "follow"
                         :headers #js {"User-Agent" ua}})
      (.then (fn [r] {:status (.-status r)
                      :content-length (.get (.-headers r) "content-length")}))
      (.catch (fn [e] {:error (str e)}))))

(defn fetch-json
  "Parsed JSON with the status alongside it. The status is returned but the
   statute judge must not branch on it -- the listing endpoint answers a
   fabricated law id with 200."
  [url]
  (-> (js/fetch url #js {:redirect "follow" :headers #js {"User-Agent" ua}})
      (.then (fn [r]
               (.then (.text r)
                      (fn [t]
                        (try {:status (.-status r) :json (js->clj (js/JSON.parse t))}
                             (catch :default _ {:status (.-status r) :bad-json true}))))))
      (.catch (fn [e] {:error (str e)}))))

(defn page-title
  "The title element's contents, verbatim. NOT trimmed: one page in the
   register really does carry a trailing space inside its title, and trimming
   would assert something the document does not say."
  [html]
  (when-let [m (re-find #"(?s)<title>(.*?)</title>" html)]
    (second m)))

(defn de-tag
  "The one text extractor. Every character count in facts.edn's header and
   every needle subtraction in verify-facts.cljs goes through this."
  [html]
  (-> html
      (str/replace #"(?is)<script.*?</script>" " ")
      (str/replace #"(?is)<style.*?</style>" " ")
      (str/replace #"(?s)<[^>]+>" " ")
      (str/replace #"\s+" " ")
      str/trim))
