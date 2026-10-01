(ns shot
  "Writes docs/images/<shot>.png: opens Babatype, types a realistic run into
   it, then has the app render a picture of itself.

   The screenshot goes through GSK from inside the process, because GNOME
   refuses D-Bus screenshots from unsandboxed callers. `ui/later!` hops the
   waiting worker back onto the GTK thread, which is the only thread allowed to
   touch widgets.

   `bb shot [babatype|babatype-results] [path]`"
  (:require [babashka.fs :as fs]
            [babatype.core :as babatype]
            [babatype.engine :as e]
            [clojure.string]
            [gtkiccup.core :as ui]
            [gtkiccup.dev :as dev]))

(def targets
  {"babatype" {:path "docs/images/babatype.png"
               :title "Babatype"
               :size [1100 700]
               :settle 2000
               ;; type a realistic run so the shot shows colour, caret and
               ;; errors rather than an untouched passage
               :start (fn []
                        (let [t0 (- (System/currentTimeMillis) 9000)
                              words (:words (:test @babatype/state))
                              text (str (clojure.string/join " " (take 7 words)) " ")
                              typed (str (subs text 0 (- (count text) 6)) "xz")]
                          (swap! babatype/state update :test
                                 (fn [t]
                                   (reduce (fn [t [i c]]
                                             ;; space carries a :char like any
                                             ;; other printable key
                                             (e/apply-key t
                                                          {:key (if (= c \space) "space" (str c))
                                                           :char c}
                                                          (+ t0 (* i 120))))
                                           t (map-indexed vector typed))))
                          (swap! babatype/state assoc :best 92))
                        (fn []))
               :app   (fn [] (babatype/app))
               :css   (fn [] babatype/css)
               :on-render (fn [_ tree] (babatype/place-caret! tree))
               :on-layout (fn [_ tree] (babatype/place-caret! tree))}
   "babatype-results"
             {:path "docs/images/babatype-results.png"
              :title "Babatype"
              :size [1100 700]
              :settle 2000
              ;; a finished 30s run at a believable ~90 wpm, with the odd slip,
              ;; so the chart and the character breakdown have real numbers.
              ;; 90 wpm is 7.5 characters a second, so 133ms a keystroke.
              :start (fn []
                       (let [ms 133
                             t0 (- (System/currentTimeMillis) 30200)
                             words (:words (:test @babatype/state))
                             ;; Fumble every seventh word by *substituting* a
                             ;; letter, not inserting one. A substitution costs
                             ;; one position and the rest stays aligned, which
                             ;; is what a real typist mostly does; an insertion
                             ;; would put everything after it out of step until
                             ;; backspaced.
                             text (->> (take 70 words)
                                       (map-indexed
                                        (fn [i w]
                                          (if (and (zero? (mod (inc i) 7))
                                                   (> (count w) 2))
                                            (str (subs w 0 1) "x" (subs w 2))
                                            w)))
                                       (clojure.string/join " "))
                             keep-n (int (/ 30000 ms))]
                         (swap! babatype/state update :test
                                (fn [t]
                                  (-> (reduce (fn [t [i c]]
                                                (e/apply-key
                                                 t
                                                 ;; space carries a :char like any
                                                 ;; other printable key
                                                 {:key (if (= c \space) "space" (str c))
                                                  :char c}
                                                 (+ t0 (* i ms))))
                                              t
                                              (map-indexed vector (take keep-n text)))
                                      (e/tick (System/currentTimeMillis)))))
                         (swap! babatype/state assoc :best 92))
                       (fn []))
              :app   (fn [] (babatype/app))
              :css   (fn [] babatype/css)
              :on-render (fn [_ tree] (babatype/place-caret! tree))
              :on-layout (fn [_ tree] (babatype/place-caret! tree))}})

(defn -main [& [which path]]
  (let [name (or which "babatype")
        {:keys [size settle title] :as t}
        (or (get targets name)
            (throw (ex-info (str "unknown target " name) {:known (keys targets)})))
        out  (or path (:path t))
        stop ((:start t))]
    (fs/create-dirs (fs/parent out))
    (try
      (ui/run ((:app t))
              :title title
              :width (first size) :height (second size)
              :window ui/chromeless-window
              ;; the caret is placed from the label's laid-out text: after each
              ;; render, and after each painted frame -- the same two hooks the
              ;; app uses
              :on-render (or (:on-render t) (fn [_ _] nil))
              :on-layout (or (:on-layout t) (fn [_ _] nil))
              :css ((:css t))
              :on-ready
              (fn [_win tree]
                (future
                  (Thread/sleep settle)
                  (ui/later!
                   (fn []
                     (let [{:keys [width height scale]} (dev/screenshot! out)]
                       (println (format "wrote %s  %dx%d px (scale %d)"
                                        out width height scale)))
                     (ui/close!))))))
      (finally (stop)))))
