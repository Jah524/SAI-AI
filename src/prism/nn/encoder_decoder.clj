(ns prism.nn.encoder-decoder
  (:require
    [clojure.pprint :refer [pprint]]
    [matrix.default :refer [transpose sum times outer minus] :as default]
    [prism.unit :refer [tanh sigmoid random-array binary-classification-error prediction-error]]
    [prism.nn.lstm :as lstm]))


(defn encoder-forward [encoder x-seq & [option]]
  (let [hidden-size (:unit-num (:hidden encoder))]
    (loop [x-seq x-seq,
           previous-activation (float-array hidden-size),
           previous-cell-state (float-array hidden-size),
           acc []]
      (if-let [x-input (first x-seq)]
        (let [model-output (lstm/lstm-activation encoder x-input previous-activation previous-cell-state option)
              {:keys [activation state]} model-output]
          (recur (rest x-seq)
                 activation
                 (:cell-state state)
                 (cons {:input x-input :hidden model-output} acc)))
        (vec (reverse acc))))))


(defn decoder-lstm-activation [model x-input recurrent-input-list encoder-input previous-cell-state & [lstm-option]]
  (let [gemv (if-let [it (:gemv lstm-option)] it default/gemv)
        lstm-layer (:hidden model)
        {:keys [block-wr block-bias input-gate-wr input-gate-bias input-gate-peephole
                forget-gate-wr forget-gate-bias forget-gate-peephole
                output-gate-wr output-gate-bias output-gate-peephole peephole unit-num
                block-we input-gate-we forget-gate-we output-gate-we ;; encoder connection
                sparses]} lstm-layer
        [block' input-gate' forget-gate' output-gate']
        (if (= (:input-type model) :sparse)
          (lstm/partial-state-sparse x-input sparses unit-num)
          (let [{:keys [block-w input-gate-w forget-gate-w output-gate-w]} lstm-layer
                lstm-mat [block-w input-gate-w forget-gate-w output-gate-w]]
            (mapv #(gemv % x-input) lstm-mat)))
        ;; recurrent-connection
        lstm-mat-r  [block-wr input-gate-wr forget-gate-wr output-gate-wr]
        [block-r' input-gate-r' forget-gate-r' output-gate-r'] (mapv #(gemv % recurrent-input-list) lstm-mat-r)
        ;; encoder connection
        lstm-mat-e  [block-we input-gate-we forget-gate-we output-gate-we]
        [block-e' input-gate-e' forget-gate-e' output-gate-e'] (mapv #(gemv % encoder-input) lstm-mat-e)
        ;; state of each gates
        block       (sum block'       block-r'       block-e'       block-bias)
        input-gate  (sum input-gate'  input-gate-r'  input-gate-e'  input-gate-bias  (times input-gate-peephole  previous-cell-state))
        forget-gate (sum forget-gate' forget-gate-r' forget-gate-e' forget-gate-bias (times forget-gate-peephole previous-cell-state))
        output-gate (sum output-gate' output-gate-r' forget-gate-e' output-gate-bias (times output-gate-peephole previous-cell-state))
        cell-state  (float-array unit-num)
        _ (dotimes [x unit-num]
            (aset ^floats cell-state x
                  (float (+ (* (tanh (aget ^floats block x)) (sigmoid (aget ^floats input-gate x)))
                            (* (sigmoid (aget ^floats forget-gate x)) (aget ^floats previous-cell-state x))))))
        lstm (float-array unit-num)
        _ (dotimes [x unit-num]
            (aset ^floats lstm x (float (* (sigmoid (aget ^floats output-gate x)) (tanh (aget ^floats cell-state x))))))]
    {:activation lstm
     :state {:lstm lstm :block block :input-gate input-gate :forget-gate forget-gate :output-gate output-gate :cell-state cell-state}}))


(defn decoder-output-activation
  [decoder decoder-hidden-list encoder-input previous-input sparse-outputs & [lstm-option]]
  (let [{:keys [output-type output]} decoder
        activation-function (condp = output-type :binary-classification sigmoid :prediction identity)]
    (if (= output-type :multi-class-classification)
      :FIXME
      (reduce (fn [acc s]
                (let [{:keys [w bias encoder-w previous-input-w]} (get output s)]
                  (assoc acc s (activation-function (+ (reduce + (times w decoder-hidden-list)) ;; use areduce fixme
                                                       (aget ^floats bias 0)
                                                       (reduce + (times encoder-w encoder-input))
                                                       (reduce + (times previous-input-w previous-input)))))))
              {}
              (vec sparse-outputs)))))


(defn decoder-activation-time-fixed
  [decoder x-input sparse-outputs previous-hidden-output encoder-input previous-input previous-cell-state & [option]]
  (let [{:keys [activation state]} (decoder-lstm-activation decoder x-input previous-hidden-output encoder-input previous-cell-state option)
        output (if (= :skip sparse-outputs) :skipped (decoder-output-activation decoder activation encoder-input previous-input sparse-outputs option))]
    {:activation {:input x-input :hidden activation :output output}
     :state  {:input x-input :hidden state}}))

(defn decoder-forward [decoder x-seq encoder-input output-items-seq & [option]]
  (let [hidden-size (:unit-num (:hidden decoder))]
    (loop [x-seq x-seq,
           output-items-seq output-items-seq,
           previous-hidden-output (float-array hidden-size),
           previous-input (float-array (:input-size decoder))
           previous-cell-state    (float-array hidden-size),
           acc []]
      (if-let [x-list (first x-seq)]
        (let [decoder-output (decoder-activation-time-fixed decoder x-list (first output-items-seq) previous-hidden-output encoder-input previous-input previous-cell-state option)
              {:keys [activation state]} decoder-output]
          (recur (rest x-seq)
                 (rest output-items-seq)
                 (:hidden activation)
                 x-list
                 (:cell-state (:hidden state))
                 (cons decoder-output acc)))
        (vec (reverse acc))))))

(defn encoder-decoder-forward
  [encoder-decoder-model encoder-x-seq decoder-x-seq decoder-output-items-seq & [option]]
  (let [gemv (if-let [it (:gemv option)] it default/gemv)
        {:keys [encoder decoder]} encoder-decoder-model
        encoder-activation (encoder-forward encoder encoder-x-seq option)
        decoder-activation (decoder-forward decoder decoder-x-seq (:activation (:hidden (last encoder-activation))) decoder-output-items-seq option)]
    {:encoder encoder-activation :decoder decoder-activation}))


;;   BPTT   ;;


(defn decoder-output-param-delta
  [item-delta-pairs decoder-hidden-size hidden-activation encoder-size encoder-input input-size previous-input]
  (->> item-delta-pairs
       (reduce (fn [acc [item delta]]
                 (assoc acc item {:w-delta    (times (float-array (repeat decoder-hidden-size delta)) hidden-activation)
                                  :bias-delta (float-array [delta])
                                  :encoder-w-delta (times (float-array (repeat encoder-size delta)) encoder-input)
                                  :previous-input-w-delta (times (float-array (repeat input-size delta)) previous-input)}))
               {})))

(defn decoder-lstm-param-delta
  [model lstm-part-delta x-input self-activation:t-1 encoder-input self-state:t-1]
  (let [{:keys [sparses unit-num]} (:hidden model)
        sparse? (= (:input-type model) :sparse)
        {:keys [block-delta input-gate-delta forget-gate-delta output-gate-delta]} lstm-part-delta]
    {:block-w-delta        (outer block-delta x-input)
     :input-gate-w-delta   (outer input-gate-delta x-input)
     :forget-gate-w-delta  (outer forget-gate-delta x-input)
     :output-gate-w-delta  (outer output-gate-delta x-input)
     ;; reccurent connection
     :block-wr-delta       (outer block-delta self-activation:t-1)
     :input-gate-wr-delta  (outer input-gate-delta self-activation:t-1)
     :forget-gate-wr-delta (outer forget-gate-delta self-activation:t-1)
     :output-gate-wr-delta (outer output-gate-delta self-activation:t-1)
     ;; encoder connection
     :block-we-delta       (outer block-delta encoder-input)
     :input-gate-we-delta  (outer input-gate-delta encoder-input)
     :forget-gate-we-delta (outer forget-gate-delta encoder-input)
     :output-gate-we-delta (outer output-gate-delta encoder-input)
     ;; bias and peephole
     :block-bias-delta       block-delta
     :input-gate-bias-delta  input-gate-delta
     :forget-gate-bias-delta forget-gate-delta
     :output-gate-bias-delta output-gate-delta
     :peephole-input-gate-delta  (times input-gate-delta  (:cell-state self-state:t-1))
     :peephole-forget-gate-delta (times forget-gate-delta (:cell-state self-state:t-1))
     :peephole-output-gate-delta (times output-gate-delta (:cell-state self-state:t-1))}))



(defn encoder-bptt
  [encoder encoder-activation propagated-delta-from-decoder & [option]]
  (let [gemv (if-let [it (:gemv option)] it default/gemv)
        {:keys [output hidden]} encoder
        {:keys [block-wr input-gate-wr forget-gate-wr output-gate-wr
                input-gate-peephole forget-gate-peephole output-gate-peephole
                unit-num]} hidden]
    ;looping latest to old
    (loop [propagated-hidden-to-hidden-delta propagated-delta-from-decoder,
           output-seq (reverse encoder-activation),
           self-delta:t+1 (lstm/lstm-delta-zeros unit-num),
           lstm-state:t+1 (lstm/gate-zeros unit-num),
           hidden-acc nil]
      (if (first output-seq)
        (let [lstm-state (:state (:hidden (first output-seq)))
              cell-state:t-1 (or (:cell-state (:state (:hidden (second output-seq)))) (float-array unit-num))
              lstm-part-delta (lstm/lstm-part-delta unit-num propagated-hidden-to-hidden-delta self-delta:t+1 lstm-state lstm-state:t+1 cell-state:t-1
                                                    input-gate-peephole forget-gate-peephole output-gate-peephole)
              x-input (:input (first output-seq))
              self-activation:t-1 (or (:activation (:hidden (second output-seq)))
                                      (float-array unit-num));when first output time (last time of bptt
              self-state:t-1      (or (:state (:hidden (second output-seq)))
                                      {:cell-state (float-array unit-num)});when first output time (last time of bptt)
              lstm-param-delta (lstm/lstm-param-delta encoder lstm-part-delta x-input self-activation:t-1 self-state:t-1)
              {:keys [block-delta input-gate-delta forget-gate-delta output-gate-delta]} lstm-part-delta
              propagated-hidden-to-hidden-delta:t-1 (->> (map (fn [w d]
                                                                (gemv (transpose unit-num w) d))
                                                              [block-wr    input-gate-wr     forget-gate-wr    output-gate-wr]
                                                              [block-delta input-gate-delta forget-gate-delta output-gate-delta])
                                                         (apply sum))]
          (recur propagated-hidden-to-hidden-delta:t-1
                 (rest output-seq)
                 lstm-part-delta
                 (:state (:hidden (first output-seq)))
                 (if (nil? hidden-acc)
                   lstm-param-delta
                   (merge-with #(if (map? %1); if sparses
                                  (merge-with (fn [accw dw]
                                                (merge-with (fn [acc d]
                                                              (sum acc d))
                                                            accw dw))
                                              %1 %2)
                                  (sum %1 %2))
                               hidden-acc lstm-param-delta))))
        {:hidden-delta hidden-acc}))))


(defn decoder-bptt
  [decoder decoder-activation encoder-input output-items-seq & [option]]
  (let [gemv (if-let [it (:gemv option)] it default/gemv)
        {:keys [output hidden encoder-size input-size]} decoder
        {:keys [block-wr input-gate-wr forget-gate-wr output-gate-wr
                block-we input-gate-we forget-gate-we output-gate-we
                input-gate-peephole forget-gate-peephole output-gate-peephole
                unit-num]} hidden]
    ;looping latest to old
    (loop [output-items-seq (reverse output-items-seq),
           propagated-hidden-to-hidden-delta nil,
           output-seq (reverse decoder-activation),
           self-delta:t+1 (lstm/lstm-delta-zeros unit-num),
           lstm-state:t+1 (lstm/gate-zeros unit-num),
           output-loss [],
           output-acc nil,
           hidden-acc nil,
           encoder-delta (float-array encoder-size)]
      (cond
        (and (= :skip (first output-items-seq)) (nil? propagated-hidden-to-hidden-delta))
        (recur (rest output-items-seq)
               nil
               (rest output-seq)
               (lstm/lstm-delta-zeros unit-num)
               (lstm/gate-zeros unit-num)
               output-loss
               nil
               nil
               encoder-delta)
        (first output-seq)
        (let [{:keys [pos neg]} (first output-items-seq);used when binary claassification
              output-delta (condp = (:output-type decoder)
                             :binary-classification
                             (binary-classification-error (:output (:activation (first output-seq))) pos neg)
                             :prediction
                             (prediction-error  (:output (:activation (first output-seq))) (first output-items-seq)))
              previous-decoder-input (if-let [it (:input (:activation (second output-seq)))] it (float-array input-size))
              output-param-delta (decoder-output-param-delta output-delta
                                                             unit-num
                                                             (:hidden (:activation (first output-seq)))
                                                             encoder-size
                                                             encoder-input
                                                             input-size
                                                             previous-decoder-input)
              propagated-output-to-hidden-delta (when-not (= :skip (first output-items-seq))
                                                  (->> output-delta
                                                       (map (fn [[item delta]]
                                                              (let [w (:w (get output item))
                                                                    v (float-array (repeat unit-num delta))]
                                                                (times w v))))
                                                       (apply sum)))
              ;merging delta: hidden-to-hidden + above-to-hidden
              summed-propagated-delta (cond (and (not= :skip (first output-items-seq)) propagated-hidden-to-hidden-delta)
                                            (sum propagated-hidden-to-hidden-delta propagated-output-to-hidden-delta)
                                            (= :skip (first output-items-seq))
                                            propagated-hidden-to-hidden-delta
                                            (nil? propagated-hidden-to-hidden-delta)
                                            propagated-output-to-hidden-delta)
              ;hidden delta
              lstm-state (:hidden (:state (first output-seq)))
              cell-state:t-1 (or (:cell-state (:hidden (:state (second output-seq)))) (float-array unit-num))
              lstm-part-delta (lstm/lstm-part-delta unit-num summed-propagated-delta self-delta:t+1 lstm-state lstm-state:t+1 cell-state:t-1
                                                    input-gate-peephole forget-gate-peephole output-gate-peephole)
              x-input (:input (:activation (first output-seq)))
              self-activation:t-1 (or (:hidden (:activation (second output-seq)))
                                      (float-array unit-num));when first output time (last time of bptt
              self-state:t-1      (or (:hidden (:state      (second output-seq)))
                                      {:cell-state (float-array unit-num)});when first output time (last time of bptt)
              lstm-param-delta (decoder-lstm-param-delta decoder lstm-part-delta x-input self-activation:t-1 encoder-input self-state:t-1)
              {:keys [block-delta input-gate-delta forget-gate-delta output-gate-delta]} lstm-part-delta
              propagated-hidden-to-hidden-delta:t-1 (->> (map (fn [w d]
                                                                (gemv (transpose unit-num w) d))
                                                              [block-wr    input-gate-wr     forget-gate-wr    output-gate-wr]
                                                              [block-delta input-gate-delta forget-gate-delta output-gate-delta])
                                                         (apply sum))
              propagation-to-encoder (->> (map (fn [w d]
                                                 (gemv (transpose unit-num w) d))
                                               [block-we    input-gate-we    forget-gate-we    output-gate-we]
                                               [block-delta input-gate-delta forget-gate-delta output-gate-delta])
                                          (apply sum))]
          (recur (rest output-items-seq)
                 propagated-hidden-to-hidden-delta:t-1
                 (rest output-seq)
                 lstm-part-delta
                 (:hidden (:state (first output-seq)))
                 (cons output-delta output-loss)
                 (if (nil? output-acc)
                   output-param-delta
                   (merge-with #(if (map? %1); if sparses
                                  (merge-with (fn [accw dw]
                                                (sum accw dw));w also bias
                                              %1 %2)
                                  (sum %1 %2))
                               output-acc
                               output-param-delta))
                 (if (nil? hidden-acc)
                   lstm-param-delta
                   (merge-with #(if (map? %1); if sparses
                                  (merge-with (fn [accw dw]
                                                (merge-with (fn [acc d]
                                                              (sum acc d))
                                                            accw dw))
                                              %1 %2)
                                  (sum %1 %2))
                               hidden-acc lstm-param-delta))
                 (sum encoder-delta propagation-to-encoder)))
        :else
        {:param-loss {:output-delta output-acc
                      :hidden-delta hidden-acc
                      :encoder-delta encoder-delta}
         :loss output-loss}))))


(defn encoder-decoder-bptt
  [encoder-decoder-model encoder-decoder-forward decoder-output-items-seq & [option]]
  (let [gemv (if-let [it (:gemv option)] it default/gemv)
        {:keys [encoder decoder]} encoder-decoder-model
        {encoder-activation :encoder decoder-activation :decoder} encoder-decoder-forward
        {loss :loss decoder-param-delta :param-loss} (decoder-bptt decoder decoder-activation  (:activation (:hidden (last encoder-activation))) decoder-output-items-seq option)
        encoder-param-delta (encoder-bptt encoder encoder-activation (:encoder-delta decoder-param-delta) option)]
    {:loss loss
     :param-loss {:encoder-param-delta encoder-param-delta :decoder-param-delta decoder-param-delta}}))


(defn update-decoder!
  [decoder param-delta-list learning-rate]
  (let [{:keys [output hidden input-size encoder-size]} decoder
        {:keys [output-delta hidden-delta]} param-delta-list
        {:keys [block-w-delta block-wr-delta block-bias-delta input-gate-w-delta input-gate-wr-delta input-gate-bias-delta
                forget-gate-w-delta forget-gate-wr-delta forget-gate-bias-delta output-gate-w-delta output-gate-wr-delta output-gate-bias-delta
                block-we-delta input-gate-we-delta forget-gate-we-delta output-gate-we-delta
                peephole-input-gate-delta peephole-forget-gate-delta peephole-output-gate-delta sparses-delta]} hidden-delta
        {:keys [block-w block-wr block-bias input-gate-w input-gate-wr input-gate-bias
                forget-gate-w forget-gate-wr forget-gate-bias output-gate-w output-gate-wr output-gate-bias
                block-we input-gate-we forget-gate-we output-gate-we
                input-gate-peephole forget-gate-peephole output-gate-peephole
                unit-num sparse? sparses]} hidden]
    ;update output connection
    (->> output-delta
         (map (fn [[item {:keys [w-delta bias-delta encoder-w-delta previous-input-w-delta]}]]
                (let [{:keys [w bias encoder-w previous-input-w]} (get output item)]
                  (aset ^floats bias 0 (float (+ (aget ^floats bias 0) (* learning-rate (aget ^floats bias-delta 0)))))
                  (dotimes [x unit-num]
                    (aset ^floats w x (float (+ (aget ^floats w x) (* learning-rate (aget ^floats w-delta x))))))
                  ;; encoder connection
                  (dotimes [x encoder-size]
                    (aset ^floats encoder-w x (float (+ (aget ^floats encoder-w x) (* learning-rate (aget ^floats encoder-w-delta x))))))
                  ;; previous decoder input
                  (dotimes [x input-size]
                    (aset ^floats previous-input-w x (float (+ (aget ^floats previous-input-w x) (* learning-rate (aget ^floats previous-input-w-delta x)))))))))
         doall)
    ;update input connection
    (dotimes [x input-size]
      (aset ^floats block-w x (float (+ (aget ^floats block-w x) (* learning-rate (aget ^floats block-w-delta x)))))
      (aset ^floats input-gate-w x (float (+ (aget ^floats input-gate-w x) (* learning-rate (aget ^floats input-gate-w-delta x)))))
      (aset ^floats forget-gate-w x (float (+ (aget ^floats forget-gate-w x) (* learning-rate (aget ^floats forget-gate-w-delta x)))))
      (aset ^floats output-gate-w x (float (+ (aget ^floats output-gate-w x) (* learning-rate (aget ^floats output-gate-w-delta x))))))
    ;update recurrent connection
    (dotimes [x (* unit-num unit-num)]
      (aset ^floats block-wr x (float (+ (aget ^floats block-wr x) (* learning-rate (aget ^floats block-wr-delta x)))))
      (aset ^floats input-gate-wr x (float (+ (aget ^floats input-gate-wr x) (* learning-rate (aget ^floats input-gate-wr-delta x)))))
      (aset ^floats forget-gate-wr x (float (+ (aget ^floats forget-gate-wr x) (* learning-rate (aget ^floats forget-gate-wr-delta x)))))
      (aset ^floats output-gate-wr x (float (+ (aget ^floats output-gate-wr x) (* learning-rate (aget ^floats output-gate-wr-delta x))))))
    (dotimes [x encoder-size]
      (aset ^floats block-we x (float (+ (aget ^floats block-we x) (* learning-rate (aget ^floats block-we-delta x)))))
      (aset ^floats input-gate-we x (float (+ (aget ^floats input-gate-we x) (* learning-rate (aget ^floats input-gate-we-delta x)))))
      (aset ^floats forget-gate-we x (float (+ (aget ^floats forget-gate-we x) (* learning-rate (aget ^floats forget-gate-we-delta x)))))
      (aset ^floats output-gate-we x (float (+ (aget ^floats output-gate-we x) (* learning-rate (aget ^floats output-gate-we-delta x))))))
    ;update lstm bias and peephole
    (dotimes [x unit-num]
      ;update bias
      (aset ^floats block-bias x (float (+ (aget ^floats block-bias x) (* learning-rate (aget ^floats block-bias-delta x)))))
      (aset ^floats input-gate-bias x (float (+ (aget ^floats input-gate-bias x) (* learning-rate (aget ^floats input-gate-bias-delta x)))))
      (aset ^floats forget-gate-bias x (float (+ (aget ^floats forget-gate-bias x) (* learning-rate (aget ^floats forget-gate-bias-delta x)))))
      (aset ^floats output-gate-bias x (float (+ (aget ^floats output-gate-bias x) (* learning-rate (aget ^floats output-gate-bias-delta x)))))
      ;and peephole
      (when (aset ^floats input-gate-peephole  x
                  (float (+ (aget ^floats input-gate-peephole x)  (* learning-rate (aget ^floats peephole-input-gate-delta  x))))))
      (when (aset ^floats forget-gate-peephole x
                  (float (+ (aget ^floats forget-gate-peephole x) (* learning-rate (aget ^floats peephole-forget-gate-delta x))))))
      (when (aset ^floats output-gate-peephole x
                  (float (+ (aget ^floats output-gate-peephole x) (* learning-rate (aget ^floats peephole-output-gate-delta x)))))))
    decoder))


(defn update-encoder-decoder!
  [encoder-decoder-model encoder-decoder-param-delta learning-rate]
  (let[{:keys [encoder decoder]} encoder-decoder-model
       {:keys [encoder-param-delta decoder-param-delta]} encoder-decoder-param-delta]
    (lstm/update-model! encoder encoder-param-delta learning-rate)
    (update-decoder!    decoder decoder-param-delta learning-rate)
    encoder-decoder-model))


(defn init-decoder
  [{:keys [input-size output-type output-items encoder-hidden-size decoder-hidden-size embedding embedding-size] :as param}]
  (let [decoder (lstm/init-model (assoc param
                                   :encoder-size encoder-hidden-size
                                   :hidden-size decoder-hidden-size
                                   :input-type :dense))
        {:keys [output hidden]} decoder
        d-output (reduce (fn [acc [word param]]
                           (assoc acc word (assoc param :encoder-w (random-array encoder-hidden-size) :previous-input-w (random-array embedding-size))))
                         {}
                         output)
        d-hidden (assoc hidden
                   :block-we       (random-array (* decoder-hidden-size encoder-hidden-size))
                   :input-gate-we  (random-array (* decoder-hidden-size encoder-hidden-size))
                   :forget-gate-we (random-array (* decoder-hidden-size encoder-hidden-size))
                   :output-gate-we (random-array (* decoder-hidden-size encoder-hidden-size)))]
    (assoc decoder :hidden d-hidden :output d-output :encoder-size encoder-hidden-size)))

(defn init-encoder-decoder-model
  [{:keys [input-size output-type output-items encoder-hidden-size decoder-hidden-size embedding embedding-size] :as param}]
  (let [encoder (lstm/init-model (-> param
                                     (dissoc :output-items)
                                     (assoc
                                       :hidden-size encoder-hidden-size
                                       :input-type :dense)))]
    {:encoder encoder :decoder (init-decoder param)}))

