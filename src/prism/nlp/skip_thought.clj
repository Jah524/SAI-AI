(ns prism.nlp.skip-thought
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]
    [clojure.java.io :refer [reader]]
    [clojure.core.async :refer [go]]
    [clj-time.local :as l]
    [clojure.core.matrix :refer [array]]
    [prism.util :as util]
    [prism.unit :as unit]
    [prism.sampling :refer [uniform->cum-uniform samples]]
    [prism.nn.encoder-decoder :as ed]))


(defn convert-rare-word-to-unk
  [wc word]
  (if (get wc word) word "<unk>"))

(defn word->feature
  [em word]
  (or (get em word) #{"<unk>"}))

(defn line->skip-thought-pairs
  [wc line]
  (let [sens (->> (str/split line #"<eos>")
                  (mapv (fn [sentence]
                          (->> (str/split sentence #" ")
                               (remove #(or (re-find #" |　" %) (= "" %)))
                               (map #(convert-rare-word-to-unk wc %))))))]
    (if (> (count sens) 1)
      (loop [sens sens,
             prev-sen nil,
             acc []]
        (if-let [sen (first sens)]
          (let [next-sen (second sens)]
            (recur (rest sens)
                   sen
                   (conj acc
                         {:encoder-x      (vec sen)
                          :decoder-prev-x (when prev-sen (->> (cons "<go>" prev-sen) vec))
                          :decoder-prev-y (when prev-sen (conj (vec prev-sen) "<eos>"))
                          :decoder-next-x (when next-sen (->> (cons "<go>" next-sen) vec))
                          :decoder-next-y (when next-sen (conj (vec next-sen) "<eos>"))})))
          acc))
      :skip)))

(defn add-negatives
  [sentence-pair negative negatives]
  (let [{:keys [decoder-prev-y decoder-next-y]} sentence-pair
        prev-y-l (count decoder-prev-y)
        next-y-l (count decoder-next-y)
        prev-negative-num (* prev-y-l negative)
        prev-negative (take prev-negative-num negatives)
        next-negative (drop prev-negative-num negatives)
        expected-num (* (+ prev-y-l next-y-l) negative)]
    (when (not= expected-num (count negatives))
      (throw (Exception. (str "Invalid negative count, expected " expected-num " negatives but actually given " (count negatives)))))
    (assoc sentence-pair
      :decoder-prev-y
      (->> decoder-prev-y
           (map-indexed
             (fn [i pos]
               {:pos (set [pos]) :neg (->> prev-negative (drop (* i negative)) (take negative) set)}))
           vec)
      :decoder-next-y
      (->> decoder-next-y
           (map-indexed
             (fn [i pos]
               {:pos (set [pos]) :neg (->> next-negative (drop (* i negative)) (take negative) set)}))
           vec))))

(defn train-skip-thought!
  [model train-path & [option]]
  (let [{:keys [interval-ms workers negative learning-rate
                skip-lines snapshot model-path]
         :or {interval-ms 60000 ;; 1 minutes
              workers 4
              negative 5
              learning-rate 0.01
              skip-lines 0
              snapshot 60 ;  1 hour when interval-ms is set 60000
              }} option
        all-lines-num (with-open [r (reader train-path)] (count (line-seq r)))
        {:keys [wc em input-type weight-type ns?]} model
        shared? (= weight-type :shared)
        neg-cum (when ns?
                  (println(str  "["(l/format-local-time (l/local-now) :basic-date-time-no-ms)"] making distribution for negative sampling ..."))
                  (let [wc-unif (-> wc (dissoc "<unk>" "<go>" "<eos>") (assoc "<eos>" all-lines-num))]
                    (-> (reduce #(assoc %1 (first %2) (float (Math/pow (second %2) (/ 3 4)))) {} wc-unif)
                        uniform->cum-uniform)))
        tmp-loss (atom 0)
        cache-size (* interval-ms 10)
        interval-counter (atom 0)
        progress-counter (atom 0)
        snapshot-num (atom 1)
        done? (atom false)
        negative-dist (atom nil)]
    (with-open [r (reader train-path)]
      (print (str "skipping " skip-lines " lines ..."))
      (loop [skip skip-lines]
        (when (> skip 0)
          (.readLine r)
          (swap! progress-counter inc)
          (recur (dec skip))))
      (println "done")
      (println "making initial distribution for negative-sampling ...")
      (when ns? (swap! negative-dist (fn[_](samples neg-cum (* negative cache-size)))))
      (println "done")
      (dotimes [w workers]
        (go (loop []
              (if-let [line (.readLine r)]
                (let [progress (/ @progress-counter all-lines-num)
                      st-pairs (line->skip-thought-pairs wc line)]
                  (swap! interval-counter inc)
                  (swap! progress-counter inc)
                  (if (= :skip st-pairs)
                    (recur)
                    (do (->> st-pairs
                             (mapv (fn [st-pair]
                                     (let [{:keys [encoder-x decoder-prev-x decoder-prev-y decoder-next-x decoder-next-y] :as training}
                                           (if ns?; = if use negative-sampling
                                             (add-negatives st-pair negative (take (* (+ (count (:decoder-prev-y st-pair))
                                                                                         (count (:decoder-next-y st-pair)))
                                                                                      negative)
                                                                                   @negative-dist))
                                             st-pair)]
                                       (try
                                         (if shared? ;when single decoder
                                           (let [encoder-x (->> encoder-x (mapv #(word->feature em %)))
                                                 decoder-prev-x (vec (cons #{"<go>"} (->> (rest decoder-prev-x) (mapv #(word->feature em %)))))
                                                 decoder-next-x (vec (cons #{"<go>"} (->> (rest decoder-next-x) (mapv #(word->feature em %)))))
                                                 [param-loss-prev loss-seq-prev] (if (empty? decoder-prev-y)
                                                                                   [nil [0]]
                                                                                   (let [forward (ed/forward model encoder-x decoder-prev-x
                                                                                                             (if ns?
                                                                                                               (map #(if (= % :skip) :skip (apply clojure.set/union (vals %))) decoder-prev-y)
                                                                                                               decoder-prev-y))
                                                                                         {:keys [param-loss loss] :as tmp} (ed/bptt model forward decoder-prev-y)
                                                                                         loss-no-skipped (->> loss (remove empty?))
                                                                                         loss-seq (->> loss-no-skipped
                                                                                                       (mapv #(/ (->> % ; by 1 target and some negatives
                                                                                                                      (map (fn [[_ v]] (Math/abs v)))
                                                                                                                      (apply +))
                                                                                                                 (if ns?
                                                                                                                   (count (last loss))
                                                                                                                   1))))];; loss per output-item]
                                                                                     [param-loss loss-seq]))
                                                 [param-loss-next loss-seq-next] (if (empty? decoder-next-y)
                                                                                   [nil [0]]
                                                                                   (let [forward-next (ed/forward model encoder-x decoder-next-x
                                                                                                                  (if ns?
                                                                                                                    (map #(if (= % :skip) :skip (apply clojure.set/union (vals %))) decoder-next-y)
                                                                                                                    decoder-next-y))
                                                                                         {param-loss-next :param-loss loss-next :loss} (ed/bptt model forward-next decoder-next-y)
                                                                                         loss-next-no-skipped (->> loss-next (remove empty?))
                                                                                         loss-seq-next (->> loss-next-no-skipped
                                                                                                            (mapv #(/ (->> % ; by 1 target and some negatives
                                                                                                                           (map (fn [[_ v]] (Math/abs v)))
                                                                                                                           (apply +))
                                                                                                                      (if ns?
                                                                                                                        (count (last loss-next))
                                                                                                                        1))))]
                                                                                     [param-loss-next loss-seq-next]))
                                                 merged-loss (+ (/ (reduce + loss-seq-prev) (count loss-seq-prev))
                                                                (/ (reduce + loss-seq-next) (count loss-seq-next)));; loss per rnn-step
                                                 ]
                                             (swap! tmp-loss #(+ %1 merged-loss))
                                             (when param-loss-prev (ed/update-model! model param-loss-prev learning-rate))
                                             (when param-loss-next (ed/update-model! model param-loss-next learning-rate)))
                                           :fixme)
                                         (catch Exception e
                                           (do
                                             ;; debug purpose
                                             (println "error has occured")
                                             (println "line\n" line)
                                             (println "words")
                                             (pprint training)
                                             (clojure.stacktrace/print-stack-trace e)
                                             (Thread/sleep 60000)))))))
                             doall)
                      (recur))))
                (reset! done? true)))))
      (loop [loop-counter 0]
        (when-not @done?
          (println (str (util/progress-format @progress-counter all-lines-num @interval-counter interval-ms "lines/s") ", loss: " (float (/ @tmp-loss (inc @interval-counter))))); loss per 1 word, and avoiding zero divide
          (reset! tmp-loss 0)
          (reset! interval-counter 0)
          (when (and model-path (not (zero? snapshot)) (not (zero? loop-counter)) (zero? (rem loop-counter snapshot)))
            (let [spath (str model-path "-SNAPSHOT-" @snapshot-num)]
              (println (str "saving " spath))
              (util/save-model model spath)
              (swap! snapshot-num inc)))
          (when ns? (swap! negative-dist (fn[_] (samples neg-cum (* negative cache-size)))))
          (Thread/sleep interval-ms)
          (recur (inc loop-counter))))
      (println "finished learning")))
  model)

(defn init-shared-skip-thought
  [wc em em-size encoder-hidden-size decoder-hidden-size rnn-type ns?]
  (let [ns? (or ns? false)
        wc-set (-> (set (keys wc)))]
    (-> (ed/init-model {:input-items #{"<go>" "<unk>"}
                        :input-size em-size
                        :encoder-hidden-size encoder-hidden-size
                        :decoder-hidden-size decoder-hidden-size
                        :output-type (if ns? :binary-classification :multi-class-classification)
                        :output-items (conj wc-set "<eos>")
                        :rnn-type rnn-type})
        (assoc
          :wc wc
          :em em
          :ns? ns?
          :weight-type :shared))))

(defn make-skip-thought
  [training-path embedding-path export-path em-size encoder-hidden-size decoder-hidden-size rnn-type shared-weight? ns? option]
  (let [_(println "making word list...")
        wc (util/make-wc training-path option)
        _(println "done")
        em (util/load-model embedding-path)
        model (if shared-weight?
                (init-shared-skip-thought wc em em-size encoder-hidden-size decoder-hidden-size rnn-type ns?)
                :fixme)]
    (train-skip-thought! model training-path (assoc option :model-path export-path))
    (print (str "Saving Skip-Thought model as " export-path " ... "))
    (util/save-model model export-path)
    (println "Done")
    model))