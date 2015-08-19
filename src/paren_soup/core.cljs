(ns paren-soup.core
  (:require [cljs.core.async :refer [chan put! <!]]
            [cljs.js :refer [empty-state eval js-eval]]
            [cljs.tools.reader :refer [read read-string *wrap-value-and-add-metadata?*]]
            [cljs.tools.reader.reader-types :refer [indexing-push-back-reader]]
            [clojure.string :refer [split-lines join replace trim triml]]
            [clojure.walk :refer [postwalk]]
            [goog.events :as events]
            [rangy.core]
            [rangy.textrange]
            [schema.core :refer [maybe either Any Str Int Keyword Bool]])
  (:require-macros [schema.core :refer [defn with-fn-validation]]
                   [cljs.core.async.macros :refer [go]]))

(defn read-safe :- (maybe (either Any js/Error))
  "Returns either a form or an exception object, or nil if EOF is reached."
  [reader :- js/Object]
  (try
    (binding [*wrap-value-and-add-metadata?* true]
      (read reader false nil))
    (catch js/Error e e)))

(defn tag-list :- [{Keyword Any}]
  "Returns a list of maps describing each tag."
  ([token :- Any]
    (tag-list token 0 0 0 0))
  ([token :- Any
    parent-level :- Int
    parent-adjust :- Int
    parent-column :- Int
    parent-line :- Int]
    (flatten
      (cond
        ; an error
        (instance? js/Error token)
        [(assoc (.-data token) :message (.-message token) :error? true :level parent-level)]
        
        ; a key-value pair from a map
        (and (coll? token) (nil? (meta token)))
        (map #(tag-list % parent-level parent-adjust parent-column parent-line) token)
        
        ; a valid token
        :else
        (let [{:keys [line column end-line end-column wrapped?]} (meta token)
              value (if wrapped? (first token) token)]
          [; begin tag
           {:line line :column column :value value
            :line-range (range (inc line) (inc end-line))}
           (if (coll? value)
             (let [delimiter-size (if (set? value) 2 1)
                   new-level (+ parent-level
                                (if (not= parent-line line)
                                  parent-adjust
                                  0))
                   new-adjust (if (list? value) 2 delimiter-size)
                   new-column (max (dec column)
                                   parent-column
                                   0)]
               [; open delimiter tags
                {:line line :column column :delimiter? true}
                {:end-line line :end-column (+ column delimiter-size) :level (+ new-level new-adjust new-column)}
                ; child tags
                (map #(tag-list % new-level new-adjust new-column line) value)
                ; close delimiter tags
                {:line end-line :column (dec end-column) :delimiter? true}
                {:end-line end-line :end-column end-column}])
             [])
           ; end tag
           {:end-line end-line :end-column end-column :level (+ parent-level parent-adjust parent-column)}])))))

(defn indent-list :- [{Keyword Any}]
  "Returns a list of maps describing each indent tag."
  [tags :- [{Keyword Any}]
   line-count :- Int]
  (let [string-lines (set (sequence (comp (filter #(string? (:value %)))
                                          (mapcat :line-range))
                                    tags))
        tags-by-line (group-by #(or (:line %) (:end-line %)) tags)]
    (loop [i 1
           current-level 0
           result (transient [])]
      (if (<= i line-count)
        (recur (inc i)
               (or (some-> (get tags-by-line i) last :level)
                   current-level)
               (if (contains? string-lines i)
                 result
                 (conj! result
                        {:line i
                         :column 1
                         :level current-level
                         :indent? true})))
        (persistent! result)))))

(defn tag->html :- Str
  "Returns an HTML string for the given tag description."
  [tag :- {Keyword Any}]
  (cond
    (:indent? tag) (str "<span class='indent'>"
                        (join (repeat (:level tag) " "))
                        "</span>")
    (:delimiter? tag) "<span class='delimiter'>"
    (:error? tag) (str "<span class='error' data-message='"
                       (:message tag)
                       "'></span>")
    (:line tag) (let [value (:value tag)]
                  (cond
                    (symbol? value) "<span class='symbol'>"
                    (list? value) "<span class='collection list'>"
                    (vector? value) "<span class='collection vector'>"
                    (map? value) "<span class='collection map'>"
                    (set? value) "<span class='collection set'>"
                    (number? value) "<span class='number'>"
                    (string? value) "<span class='string'>"
                    (keyword? value) "<span class='keyword'>"
                    (nil? value) "<span class='nil'>"
                    (contains? #{true false} value) "<span class='boolean'>"
                    :else "<span>"))
    (:end-line tag) "</span>"
    :else "<span>"))

(defn line->html :- Str
  "Returns the given line with html added."
  [line :- Str
   tags-for-line :- [{Keyword Any}]]
  (let [get-column #(or (:column %) (:end-column %))
        tags-for-line (sort-by get-column tags-for-line)
        html-per-column (sequence (comp (partition-by get-column)
                                        (map #(join (map tag->html %))))
                                  tags-for-line)
        columns (set (map get-column tags-for-line))
        segments (loop [i 0
                        segments (transient [])
                        current-segment (transient [])]
                   (if-let [c (get line i)]
                     (if (contains? columns (inc i))
                       (recur (inc i)
                              (conj! segments (persistent! current-segment))
                              (transient [c]))
                       (recur (inc i)
                              segments
                              (conj! current-segment c)))
                     (->> (persistent! current-segment)
                          (conj! segments)
                          persistent!
                          (map join))))]
    (join (interleave segments (concat html-per-column (repeat ""))))))

(defn lines->html :- [Str]
  "Returns the lines with html added."
  [lines :- [Str]]
  (let [reader (indexing-push-back-reader (join \newline lines))
        tags (sequence (comp (take-while some?) (mapcat tag-list))
                       (repeatedly (partial read-safe reader)))
        tags (concat (indent-list tags (count lines)) tags)
        get-line #(or (:line %) (:end-line %))
        tags-by-line (group-by get-line tags)]
    (sequence (comp (partition-all 2)
                    (map (fn [[i line]]
                           (line->html line (get tags-by-line i)))))
              (interleave (iterate inc 1) lines))))

(defn eval-forms
  "Evals all the supplied forms."
  ([forms cb]
    (let [state (empty-state)
          opts {:eval js-eval
                :source-map true
                :context :expr}]
      (eval state '(ns cljs.user) opts
            #(eval-forms forms cb state opts (transient [])))))
  ([forms cb state opts results]
    (if (seq forms)
      (let [[form & forms] forms
            new-ns (when (and (list? form) (= 'ns (first form)))
                     (second form))]
        (try
          (eval state form opts
                (fn [res]
                  (let [error? (instance? js/Error (:error res))
                        res (if error? (:error res) res)
                        opts (if (and new-ns (not error?)) (assoc opts :ns new-ns) opts)]
                    (eval-forms forms cb state opts (conj! results res)))))
          (catch js/Error e
            (eval-forms forms cb state opts (conj! results e)))))
      (cb (persistent! results)))))

(defn instarepl!
  "Evals the forms from content and puts the results in the instarepl."
  [instarepl :- js/Object
   content :- js/Object]
  (let [elems (vec (for [elem (-> content .-children array-seq)
                         :let [classes (.-classList elem)]
                         :when (or (.contains classes "collection")
                                   (.contains classes "symbol"))]
                     elem))
        forms (for [elem elems]
                (->> elem .-textContent read-string))
        instarepl-top (-> instarepl .getBoundingClientRect .-top)
        cb (fn [results]
             (set! (.-innerHTML instarepl)
                   (loop [i 0
                          offset 0
                          evals (transient [])]
                     (if-let [elem (get elems i)]
                       (let [top (-> elem .getBoundingClientRect
                                     .-top (- instarepl-top))
                             height (-> elem .getBoundingClientRect
                                        .-bottom (- instarepl-top) (- top))
                             res (get results i)]
                         (recur (inc i)
                                (+ offset height)
                                (conj! evals
                                       (str "<div class='result"
                                            (when (instance? js/Error res)
                                              " error")
                                            "' "
                                            "style='top: "
                                            (- top offset)
                                            "px; height: "
                                            height
                                            "px;'>"
                                            (pr-str res)
                                            "</div>"))))
                       (join (persistent! evals))))))]
    (eval-forms forms cb)))

(def rainbow-colors ["aqua" "brown" "cornflowerblue"  "fuchsia" "orange"
                     "hotpink" "lime" "orange" "plum" "tomato"])

(defn rainbow-delimiters :- {js/Object Str}
  "Returns a map of elements and colors."
  [parent :- js/Object
   level :- Int]
  (apply merge
         {}
         (for [elem (-> parent .-children array-seq)]
           (cond
             (-> elem .-classList (.contains "delimiter"))
             {elem (get rainbow-colors (mod level (count rainbow-colors)))}
             (-> elem .-classList (.contains "collection"))
             (apply merge {} (rainbow-delimiters elem (inc level)))
             :else
             {}))))

(defn line-numbers :- Str
  "Adds line numbers to the numbers."
  [line-count :- Int]
  (join (for [i (range line-count)]
          (str "<div>" (inc i) "</div>"))))

(defn split-lines-without-indent :- [Str]
  "Splits the string into lines while removing all indentation."
  [s :- Str]
  (let [s (if-not (= \newline (last s))
            (str s "\n ")
            (str s " "))
        lines (map triml (split-lines s))
        last-line (last lines)
        last-line-len (max 0 (dec (count last-line)))]
    (conj (vec (butlast lines))
          (subs last-line 0 last-line-len))))

(defn refresh!
  "Refreshes the contents."
  [instarepl :- (maybe js/Object)
   numbers :- (maybe js/Object)
   content :- js/Object
   events-chan :- Any]
  (let [html (.-innerHTML content)]
    (set! (.-innerHTML content)
          (if (>= (.indexOf html "<br>") 0)
            (-> html (replace "<br>" \newline) (replace "</br>" ""))
            (-> html (replace "<div>" \newline) (replace "</div>" "")))))
  (let [lines (split-lines-without-indent (.-textContent content))]
    (set! (.-innerHTML content) (join \newline (lines->html lines)))
    (doseq [elem (-> content (.querySelectorAll ".error") array-seq)]
      (events/listen elem "mouseenter" #(put! events-chan %))
      (events/listen elem "mouseleave" #(put! events-chan %)))
    (when numbers
      (set! (.-innerHTML numbers) (line-numbers (dec (count lines)))))
    (when instarepl
      (instarepl! instarepl content)))
  (doseq [[elem color] (rainbow-delimiters content -1)]
    (set! (-> elem .-style .-color) color)))

(defn move-caret!
  "Moves the caret as necessary."
  [content :- js/Object
   char-range :- js/Object
   char-code :- Int]
  (let [text (.-textContent content)
        caret-position (.-start char-range)
        ; get the character before the caret (not including spaces)
        prev-position (loop [i (dec caret-position)]
                        (if (= " " (get text i))
                          (recur (dec i))
                          i))
        prev-char (get text prev-position)
        ; get the character after the caret (not including spaces)
        next-position (loop [i caret-position]
                        (if (= " " (get text i))
                          (recur (inc i))
                          i))
        next-char (get text next-position)]
    (case char-code
      13 ; return
      (when (not= next-position caret-position)
        (set! (.-start char-range) next-position)
        (set! (.-end char-range) next-position))
      8 ; backspace
      (when (and (not= prev-position (dec caret-position))
                 (= prev-char \newline))
        (set! (.-start char-range) prev-position)
        (set! (.-end char-range) prev-position)
        (let [range (.createRange js/rangy)
              caret-position (if (contains? #{\) \] \} \newline} next-char)
                               (inc caret-position)
                               caret-position)]
          (.selectCharacters range content prev-position caret-position)
          (.deleteContents range)))
      nil)))

(defn init! []
  (.init js/rangy)
  (doseq [elem (-> js/document (.querySelectorAll ".paren-soup") array-seq)]
    (let [instarepl (.querySelector elem ".paren-soup-instarepl")
          numbers (.querySelector elem ".paren-soup-numbers")
          content (.querySelector elem ".paren-soup-content")
          events-chan (chan)]
      (set! (.-spellcheck elem) false)
      (when content
        (refresh! instarepl numbers content events-chan)
        (events/removeAll content)
        (events/listen content "keydown" #(put! events-chan %))
        (events/listen content "DOMCharacterDataModified" #(put! events-chan %))
        (go (while true
              (let [event (<! events-chan)]
                (case (.-type event)
                  "DOMCharacterDataModified"
                  (let [sel (.getSelection js/rangy)
                        ranges (.saveCharacterRanges sel content)]
                    (refresh! instarepl numbers content events-chan)
                    (.restoreCharacterRanges sel content ranges))
                  "keydown"
                  (let [char-code (.-keyCode event)]
                    (when (contains? #{8 13} char-code)
                      (let [sel (.getSelection js/rangy)
                            ranges (.saveCharacterRanges sel content)]
                        (refresh! instarepl numbers content events-chan)
                        (when-let [char-range (some-> ranges (get 0) .-characterRange)]
                          (move-caret! content char-range char-code))
                        (.restoreCharacterRanges sel content ranges))))
                  "mouseenter"
                  (let [elem (.-target event)
                        x (.-clientX event)
                        y (.-clientY event)]
                    (let [popup (.createElement js/document "div")]
                      (aset popup "textContent" (-> elem .-dataset .-message))
                      (aset (.-style popup) "top" (str y "px"))
                      (aset (.-style popup) "left" (str x "px"))
                      (aset popup "className" "error-text")
                      (.appendChild (.-body js/document) popup)))
                  "mouseleave"
                  (doseq [elem (-> js/document .-body (.querySelectorAll ".error-text") array-seq)]
                    (-> js/document .-body (.removeChild elem)))
                  nil))))))))

(defn init-with-validation! []
  (.log js/console (with-out-str (time (with-fn-validation (init!))))))

(set! (.-onload js/window) init!)
