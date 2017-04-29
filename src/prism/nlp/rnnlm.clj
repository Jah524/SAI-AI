(ns prism.nlp.rnnlm
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]
    [clojure.java.io :refer [reader]]
    [clojure.core.async :refer [go]]
    [prism.nn.lstm :as lstm];:refer [lstm-activation init-model train!]]
    [prism.util :as util]
    [prism.sampling :refer [uniform->cum-uniform samples]]
    [matrix.default :as default]))

(defn convert-rare-word-to-unk
  [wc word]
  (if (get wc word) word "<unk>"))

(defn tok->rnnlm-pairs
  [wc tok-line]
  (let [words (->> (str/split tok-line #" ")
                   (remove #(or (re-find #" |　" %) (= "" %)))
                   (map #(convert-rare-word-to-unk wc %)))]
    (if (empty? words)
      :skip
      (loop [coll words,
             x-acc []
             y-acc []]
        (if-let [f (first coll)]
          (let [s (or (second coll) "<eos>")]
            (recur (rest coll)
                   (conj x-acc (set [f]))
                   (conj y-acc {:pos (set [s])})))
          {:x x-acc :y y-acc})))))

(defn add-negatives
  [rnnlm-pair negative negatives]
  (let [{:keys [y]} rnnlm-pair]
    (when (not= (* (count y) negative) (count negatives))
      (throw (Exception. "Invalid negative count")))
    (assoc rnnlm-pair
      :y
      (->> y
           (map-indexed
             (fn [i train]
               (assoc train :neg (->> negatives (drop (* i negative)) (take negative) set))))))))


(defn train-rnnlm!
  [model train-path & [option]]
  (let [{:keys [interval-ms workers negative initial-learning-rate min-learning-rate
                snapshot snapshot-path]
         :or {interval-ms 60000 ;; 1 minutes
              workers 4
              negative 5
              initial-learning-rate 0.025
              min-learning-rate 0.001
              snapshot 60}} option
        all-lines-num (with-open [r (reader train-path)] (count (line-seq r)))
        {:keys [wc em input-type]} model
        wc-unif (reduce #(assoc %1 (first %2) (float (Math/pow (second %2) (/ 3 4)))) {} (dissoc wc "<unk>"))
        neg-cum (uniform->cum-uniform wc-unif)
        tmp-loss (atom 0)
        cache-size 100000
        local-counter (atom 0)
        done? (atom false)]
    (with-open [r (reader train-path)]
      (dotimes [w workers]
        (go (loop [negatives (samples neg-cum (* negative cache-size))]
              (if-let [line (.readLine r)]
                (let [progress (/ @local-counter all-lines-num)
                      learning-rate (max (- initial-learning-rate (* initial-learning-rate progress)) min-learning-rate)
                      rnnlm-pair (tok->rnnlm-pairs wc line)]
                  (swap! local-counter inc)
                  (if (= :skip rnnlm-pair)
                    (recur negatives)
                    (let [neg-pool-num (* negative 10); (count (:x rnnlm-pair)))
                          neg-pool    (take neg-pool-num negatives)
                          rest-negatives (drop neg-pool-num negatives)
                          {:keys [x y]} (add-negatives rnnlm-pair negative (shuffle (take (* (count (:x rnnlm-pair)) negative)
                                                                                          (cycle neg-pool))))]
                      (try
                        (let [forward (lstm/sequential-output model x (map #(apply clojure.set/union (vals %)) y))
                              {:keys [param-loss loss]} (lstm/bptt model forward y)
                              loss-seq (->> loss
                                            (map #(/ (->> % ; by 1 target and some negatives
                                                          (map (fn [[_ v]] (Math/abs v)))
                                                          (reduce +))
                                                     (inc negative))))];; loss per output-item
                          (swap! tmp-loss #(+ %1 (/ (reduce + loss-seq) (count loss-seq))));; loss per word in line
                          (lstm/update-model! model param-loss learning-rate))
                        (catch Exception e
                          (do
                            ;; debug purpose
                            (clojure.stacktrace/print-stack-trace e)
                            (println line)
                            (pprint x)
                            (pprint y)
                            (Thread/sleep 60000))))
                      (recur (if (< (count rest-negatives) (* 10 negative))
                               (samples neg-cum (* negative cache-size))
                               rest-negatives)))))
                (reset! done? true)))))
      (loop [counter 0, snapshot-counter 0]
        (when-not @done?
          (let [c @local-counter
                next-counter (+ counter c)]
            (println (str (util/progress-format counter all-lines-num c interval-ms "lines/s") ", loss: " (float @tmp-loss)))
            (reset! tmp-loss 0)
            (reset! local-counter 0)
            (when (and snapshot-path (not (zero? snapshot-counter)) (not (zero? (rem snapshot-counter snapshot))))
              (let [spath (str snapshot-path "-SNAPSHOT-" snapshot-counter)]
                (println (str "saving " spath))
                (util/save-model (dissoc (lstm/convert-model model default/default-matrix-kit) :matrix-kit) spath)))
            (Thread/sleep interval-ms)
            (recur next-counter (inc snapshot-counter)))))
      (println "finished learning")))
  model)


(defn init-rnnlm-model
  [wc hidden-size {:keys [matrix-kit] :or {matrix-kit default/default-matrix-kit}}]
  (let [wc-set (conj (set (keys wc)) "<eos>")]
    (-> (lstm/init-model {:input-type :sparse
                          :input-items wc-set
                          :input-size nil
                          :hidden-size hidden-size
                          :output-type :binary-classification
                          :output-items wc-set
                          :activation :linear
                          :matrix-kit matrix-kit})
        (assoc :wc wc))))

(defn make-rnnlm
  [training-path export-path hidden-size option]
  (let [_(println "making word list...")
        wc (util/make-wc training-path option)
        _(println "done")
        model (init-rnnlm-model wc hidden-size option)
        model-path     (str export-path ".rnnlm")]
    (train-rnnlm! model training-path (assoc option :snapshot-path model-path))
    (let [m (dissoc (lstm/convert-model model default/default-matrix-kit) :matrix-kit)]
      (print (str "Saving RNNLM model as " model-path " ... "))
      (util/save-model m model-path)
      (println "Done"))
    model))

(defn resume-train
  [training-path model-path option]
  (let [model (util/load-model model-path)
        updated-model (train-rnnlm! model training-path option)]
    (println (str "Saving RNNLM model as " model-path))
    (util/save-model updated-model model-path)
    model))

(defn load-model
  [model-path matrix-kit]
  (lstm/load-model model-path matrix-kit))

(defn text-vector [model words]
  (let [{:keys [hidden matrix-kit wc]} model
        {:keys [make-vector]} matrix-kit
        hidden-size (:unit-num hidden)]
    (loop [words words,
           previous-activation (make-vector hidden-size),
           previous-cell-state (make-vector hidden-size)]
      (if-let [word (first words)]
        (let [word (if (get wc word) word "<unk>")
              {:keys [activation state]} (lstm/lstm-activation model (set [word]) previous-activation previous-cell-state)]
          (recur (rest words)
                 activation
                 (:cell-state state)))
        previous-activation))))

(defn text-similarity
  [model words1 words2 l2?]
  (util/similarity (text-vector model words1) (text-vector model words2) l2?))

