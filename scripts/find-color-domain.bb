#!/usr/bin/env bb

(require '[clojure.string :as str])

(import '[java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
        '[java.net HttpURLConnection InetSocketAddress Socket URL]
        '[java.nio.charset StandardCharsets]
        '[java.util ArrayList Collections Random])

(def css-colors
  ["aliceblue" "antiquewhite" "aqua" "aquamarine" "azure"
   "beige" "bisque" "black" "blanchedalmond" "blue" "blueviolet" "brown" "burlywood"
   "cadetblue" "chartreuse" "chocolate" "coral" "cornflowerblue" "cornsilk" "crimson" "cyan"
   "darkblue" "darkcyan" "darkgoldenrod" "darkgray" "darkgreen" "darkgrey" "darkkhaki"
   "darkmagenta" "darkolivegreen" "darkorange" "darkorchid" "darkred" "darksalmon"
   "darkseagreen" "darkslateblue" "darkslategray" "darkslategrey" "darkturquoise" "darkviolet"
   "deeppink" "deepskyblue" "dimgray" "dimgrey" "dodgerblue"
   "firebrick" "floralwhite" "forestgreen" "fuchsia"
   "gainsboro" "ghostwhite" "gold" "goldenrod" "gray" "green" "greenyellow" "grey"
   "honeydew" "hotpink"
   "indianred" "indigo" "ivory"
   "khaki"
   "lavender" "lavenderblush" "lawngreen" "lemonchiffon" "lightblue" "lightcoral" "lightcyan"
   "lightgoldenrodyellow" "lightgray" "lightgreen" "lightgrey" "lightpink" "lightsalmon"
   "lightseagreen" "lightskyblue" "lightslategray" "lightslategrey" "lightsteelblue" "lightyellow"
   "lime" "limegreen" "linen"
   "magenta" "maroon" "mediumaquamarine" "mediumblue" "mediumorchid" "mediumpurple"
   "mediumseagreen" "mediumslateblue" "mediumspringgreen" "mediumturquoise" "mediumvioletred"
   "midnightblue" "mintcream" "mistyrose" "moccasin"
   "navajowhite" "navy"
   "oldlace" "olive" "olivedrab" "orange" "orangered" "orchid"
   "palegoldenrod" "palegreen" "paleturquoise" "palevioletred" "papayawhip" "peachpuff" "peru"
   "pink" "plum" "powderblue" "purple"
   "rebeccapurple" "red" "rosybrown" "royalblue"
   "saddlebrown" "salmon" "sandybrown" "seagreen" "seashell" "sienna" "silver" "skyblue"
   "slateblue" "slategray" "slategrey" "snow" "springgreen" "steelblue"
   "tan" "teal" "thistle" "tomato" "transparent" "turquoise"
   "violet"
   "wheat" "white" "whitesmoke"
   "yellow" "yellowgreen"])

(def preferred-color-by-letter
  {"a" "aliceblue"
   "b" "blue"
   "c" "cyan"
   "d" "darkblue"
   "f" "fuchsia"
   "g" "green"
   "h" "hotpink"
   "i" "indigo"
   "k" "khaki"
   "l" "lime"
   "m" "magenta"
   "n" "navy"
   "o" "orange"
   "p" "purple"
   "r" "red"
   "s" "silver"
   "t" "teal"
   "v" "violet"
   "w" "white"
   "y" "yellow"})

(def colors-by-letter
  (->> css-colors
       (group-by #(subs % 0 1))
       (into (sorted-map))))

(def letters
  (vec (keys colors-by-letter)))

(def representative-color-by-letter
  (into {}
        (for [[letter colors] colors-by-letter]
          [letter (or (preferred-color-by-letter letter)
                      (first (sort colors)))])))

(def default-opts
  {:delay-ms 1000
   :timeout-ms 10000
   :max-results 1
   :max-checks nil
   :method :both
   :shuffle? false
   :seed nil
   :verbose? false
   :print-candidates? false})

(def usage
  (str/join
   \newline
   ["Find available getXYZ.com domains where X/Y/Z are distinct CSS color initials."
    ""
    "Usage:"
    "  bb scripts/find-color-domain.bb [options]"
    ""
    "Options:"
    "  --delay-ms N       Milliseconds to wait between domain checks (default: 1000)."
    "  --timeout-ms N     Per-request RDAP/WHOIS timeout in milliseconds (default: 10000)."
    "  --max-results N    Stop after N available domains. Use 0 for no result limit (default: 1)."
    "  --max-checks N     Check at most N candidate domains after ordering/shuffling."
    "  --method M         rdap, whois, or both (default: both)."
    "  --shuffle          Randomize candidate order before checking."
    "  --seed N           Seed used with --shuffle for repeatable ordering."
    "  --verbose          Print registered/unknown results to stderr too."
    "  --print-candidates Print candidates and meanings without checking availability."
    "  -h, --help         Show this help."
    ""
    "Available CSS initials:"
    (str "  " (str/join " " letters))
    ""
    "Available domains are printed to stdout like:"
    "  getrgb.com  red / green / blue"
    ""
    "Notes:"
    "  RDAP 404 / WHOIS 'No match' means the domain is not currently in the .com registry."
    "  A registrar can still mark an unregistered domain as premium/reserved/unavailable."]))

(defn parse-non-negative-int [opt s]
  (try
    (let [n (Long/parseLong s)]
      (when (neg? n)
        (throw (ex-info (str opt " must be non-negative") {:opt opt :value s})))
      n)
    (catch NumberFormatException _
      (throw (ex-info (str opt " must be an integer") {:opt opt :value s})))))

(defn parse-positive-int [opt s]
  (let [n (parse-non-negative-int opt s)]
    (when (zero? n)
      (throw (ex-info (str opt " must be greater than zero") {:opt opt :value s})))
    n))

(defn require-value [args opt]
  (when (empty? args)
    (throw (ex-info (str opt " requires a value") {:opt opt})))
  [(first args) (rest args)])

(defn parse-args [args]
  (loop [opts default-opts
         args (seq args)]
    (if (empty? args)
      opts
      (let [arg (first args)
            more (rest args)]
        (case arg
          ("-h" "--help")
          (assoc opts :help? true)

          "--delay-ms"
          (let [[value more] (require-value more arg)]
            (recur (assoc opts :delay-ms (parse-non-negative-int arg value)) more))

          "--timeout-ms"
          (let [[value more] (require-value more arg)]
            (recur (assoc opts :timeout-ms (parse-positive-int arg value)) more))

          "--max-results"
          (let [[value more] (require-value more arg)]
            (recur (assoc opts :max-results (parse-non-negative-int arg value)) more))

          "--max-checks"
          (let [[value more] (require-value more arg)]
            (recur (assoc opts :max-checks (parse-positive-int arg value)) more))

          "--method"
          (let [[value more] (require-value more arg)
                method (keyword (str/lower-case value))]
            (when-not (#{:rdap :whois :both} method)
              (throw (ex-info "--method must be rdap, whois, or both" {:opt arg :value value})))
            (recur (assoc opts :method method) more))

          "--shuffle"
          (recur (assoc opts :shuffle? true) more)

          "--seed"
          (let [[value more] (require-value more arg)]
            (recur (assoc opts :seed (parse-non-negative-int arg value)) more))

          "--verbose"
          (recur (assoc opts :verbose? true) more)

          "--print-candidates"
          (recur (assoc opts :print-candidates? true) more)

          (throw (ex-info (str "Unknown option: " arg) {:opt arg})))))))

(defn candidate-maps []
  (for [a letters
        b letters
        c letters
        :when (and (not= a b)
                   (not= a c)
                   (not= b c))
        :let [colors [(representative-color-by-letter a)
                      (representative-color-by-letter b)
                      (representative-color-by-letter c)]]
        :when (apply distinct? colors)]
    {:letters (str a b c)
     :domain (str "get" a b c ".com")
     :colors colors}))

(defn maybe-shuffle [xs {:keys [shuffle? seed]}]
  (if-not shuffle?
    xs
    (let [copy (ArrayList. xs)
          rng (if (some? seed)
                (Random. seed)
                (Random.))]
      (Collections/shuffle copy rng)
      (vec copy))))

(defn ordered-candidates [{:keys [max-checks] :as opts}]
  (let [candidates (-> (candidate-maps) vec (maybe-shuffle opts))]
    (cond->> candidates
      max-checks (take max-checks)
      true vec)))

(defn format-candidate [{:keys [domain colors]}]
  (format "%-11s  %s" domain (str/join " / " colors)))

(defn rdap-url [domain]
  (str "https://rdap.verisign.com/com/v1/domain/" (str/upper-case domain)))

(defn rdap-check [{:keys [timeout-ms]} domain]
  (try
    (let [^HttpURLConnection conn (.openConnection (URL. (rdap-url domain)))]
      (.setConnectTimeout conn timeout-ms)
      (.setReadTimeout conn timeout-ms)
      (.setRequestProperty conn "Accept" "application/rdap+json, application/json")
      (.setRequestProperty conn "User-Agent" "green-color-domain-finder/1.0")
      (let [status (.getResponseCode conn)]
        (.disconnect conn)
        (case status
          200 {:status :registered :source :rdap :http-status status}
          404 {:status :available :source :rdap :http-status status}
          {:status :unknown
           :source :rdap
           :http-status status
           :message (str "Unexpected RDAP HTTP status " status)})))
    (catch Exception e
      {:status :unknown
       :source :rdap
       :message (.getMessage e)})))

(defn read-whois-response [host port query timeout-ms]
  (with-open [socket (Socket.)]
    (.connect socket (InetSocketAddress. host port) timeout-ms)
    (.setSoTimeout socket timeout-ms)
    (with-open [out (BufferedWriter.
                     (OutputStreamWriter. (.getOutputStream socket) StandardCharsets/US_ASCII))
                in (BufferedReader.
                    (InputStreamReader. (.getInputStream socket) StandardCharsets/UTF_8))]
      (.write out (str query "\r\n"))
      (.flush out)
      (let [sb (StringBuilder.)]
        (loop []
          (when-let [line (.readLine in)]
            (.append sb line)
            (.append sb \newline)
            (recur)))
        (str sb)))))

(defn whois-check [{:keys [timeout-ms]} domain]
  (try
    (let [body (read-whois-response "whois.verisign-grs.com" 43 (str "=" domain) timeout-ms)
          domain-pattern (re-pattern (str "(?im)^\\s*Domain Name:\\s*" (java.util.regex.Pattern/quote (str/upper-case domain)) "\\s*$"))]
      (cond
        (re-find #"(?i)\bNo match for\b" body)
        {:status :available :source :whois}

        (re-find domain-pattern body)
        {:status :registered :source :whois}

        (re-find #"(?i)(rate limit|limit exceeded|quota|too many queries)" body)
        {:status :unknown :source :whois :message "WHOIS rate limit or quota response"}

        :else
        {:status :unknown :source :whois :message "Could not classify WHOIS response"}))
    (catch Exception e
      {:status :unknown :source :whois :message (.getMessage e)})))

(defn check-domain [{:keys [method] :as opts} domain]
  (case method
    :rdap (rdap-check opts domain)
    :whois (whois-check opts domain)
    :both (let [rdap (rdap-check opts domain)]
            (if (= :unknown (:status rdap))
              (assoc (whois-check opts domain) :rdap rdap)
              rdap))))

(defn status-message [{:keys [status source http-status message rdap]}]
  (str (name status)
       " via " (name source)
       (when http-status (str " HTTP " http-status))
       (when message (str " (" message ")"))
       (when rdap (str " after RDAP " (name (:status rdap))))))

(defn sleep-before-check! [delay-ms checked-count]
  (when (and (pos? delay-ms) (pos? checked-count))
    (Thread/sleep delay-ms)))

(defn run-checks [opts candidates]
  (let [total (count candidates)
        unlimited? (zero? (:max-results opts))]
    (loop [remaining candidates
           checked 0
           found 0]
      (cond
        (empty? remaining)
        found

        (and (not unlimited?) (>= found (:max-results opts)))
        found

        :else
        (let [candidate (first remaining)
              domain (:domain candidate)]
          (sleep-before-check! (:delay-ms opts) checked)
          (binding [*out* *err*]
            (println (format "[%d/%d] checking %s" (inc checked) total domain)))
          (let [result (check-domain opts domain)]
            (case (:status result)
              :available
              (do
                (println (format-candidate candidate))
                (recur (rest remaining) (inc checked) (inc found)))

              :registered
              (do
                (when (:verbose? opts)
                  (binding [*out* *err*]
                    (println (str (format-candidate candidate) "  " (status-message result)))))
                (recur (rest remaining) (inc checked) found))

              :unknown
              (do
                (binding [*out* *err*]
                  (println (str (format-candidate candidate) "  " (status-message result))))
                (recur (rest remaining) (inc checked) found)))))))))

(defn -main [& args]
  (try
    (let [opts (parse-args args)]
      (if (:help? opts)
        (do
          (println usage)
          0)
        (let [candidates (ordered-candidates opts)]
          (if (:print-candidates? opts)
            (do
              (doseq [candidate candidates]
                (println (format-candidate candidate)))
              0)
            (let [found (run-checks opts candidates)]
              (binding [*out* *err*]
                (println (format "Checked %d candidate(s); found %d available domain(s)."
                                 (count candidates)
                                 found)))
              (if (pos? found) 0 1))))))
    (catch clojure.lang.ExceptionInfo e
      (binding [*out* *err*]
        (println "Error:" (.getMessage e))
        (println)
        (println usage))
      2)))

(when (= *file* (System/getProperty "babashka.file"))
  (System/exit (apply -main *command-line-args*)))
