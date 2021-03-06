(ns prism.nn.encoder-decoder.gru
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.core.matrix :refer [add add! sub sub! emap esum emul emul! mmul outer-product transpose array dot exp] :as m]
    [clojure.core.matrix.operators :as o]
    [prism.unit :refer [sigmoid tanh init-orthogonal-matrix init-vector init-matrix error merge-param!]]
    [prism.util :as util]
    [prism.optimizer :refer [update-param!]]
    [prism.nn.rnn.gru :as gru]
    [prism.nn.encoder-decoder.lstm :as lstm]))


(defn decoder-gru-activation [decoder x-input hidden:t-1 encoder-input]
  (let [{:keys [hidden hidden-size]} decoder
        {:keys [w wr bias
                update-gate-w update-gate-wr update-gate-bias
                reset-gate-w reset-gate-wr reset-gate-bias
                we update-gate-we reset-gate-we
                sparses]} hidden
        [unit' update-gate' reset-gate'] (if (or (set? x-input) (map? x-input))
                                           (gru/partial-state-sparse x-input sparses)
                                           (let [{:keys [w update-gate-w reset-gate-w]} hidden
                                                 gru-mat [w update-gate-w reset-gate-w]]
                                             (mapv #(mmul % x-input) gru-mat)))
        update-gate-state (add update-gate' (mmul update-gate-wr hidden:t-1) (mmul update-gate-we encoder-input) update-gate-bias)
        reset-gate-state  (add reset-gate'  (mmul reset-gate-wr hidden:t-1)  (mmul reset-gate-we encoder-input)  reset-gate-bias)
        update-gate (m/logistic update-gate-state)
        reset-gate  (m/logistic reset-gate-state)
        h-state (add! (mmul wr (emul reset-gate hidden:t-1))
                      unit'
                      (mmul we encoder-input)
                      bias)
        h (m/tanh h-state)
        gru (add! (emul update-gate h)
                  (emul! (sub! (array :vectorz (repeat hidden-size 1)) update-gate)
                         hidden:t-1))]
    {:activation  {:gru gru :update-gate update-gate :reset-gate reset-gate :h h}
     :state       {:update-gate update-gate-state :reset-gate reset-gate-state :h-state h-state}}))

(defn decoder-activation-time-fixed
  [decoder x-input sparse-outputs hidden:t-1 encoder-input previous-input]
  (let [{:keys [activation state]} (decoder-gru-activation decoder x-input hidden:t-1 encoder-input)
        output (if (= :skip sparse-outputs) :skipped (lstm/decoder-output-activation decoder (:gru activation) encoder-input previous-input sparse-outputs))]
    {:activation {:input x-input :hidden activation :output output}
     :state  {:input x-input :hidden state}}))

(defn decoder-forward
  [decoder x-seq encoder-input output-items-seq]
  (let [{:keys [hidden hidden-size input-size]} decoder]
    (loop [x-seq x-seq,
           output-items-seq output-items-seq,
           hidden:t-1 (array :vectorz (repeat hidden-size 0)),
           previous-input (array :vectorz (repeat input-size 0)),
           acc []]
      (if-let [x-list (first x-seq)]
        (let [{:keys [activation state] :as decoder-output}
              (decoder-activation-time-fixed decoder x-list (first output-items-seq) hidden:t-1 encoder-input previous-input)]
          (recur (rest x-seq)
                 (rest output-items-seq)
                 (:gru (:hidden activation))
                 x-list
                 (cons decoder-output acc)))
        (vec (reverse acc))))))


(defn encoder-decoder-forward
  [encoder-decoder-model encoder-x-seq decoder-x-seq decoder-output-items-seq]
  (let [{:keys [encoder decoder]} encoder-decoder-model
        encoder-activation (gru/context encoder encoder-x-seq)
        decoder-activation (decoder-forward decoder decoder-x-seq (:gru (:activation (:hidden (last encoder-activation)))) decoder-output-items-seq)]
    {:encoder encoder-activation :decoder decoder-activation}))



(defn gru-param-delta
  [model gru-delta x-input hidden:t-1 encoder-input]
  (let [{:keys [hidden hidden-size]} model
        {:keys [sparses]} hidden
        {:keys [unit-delta update-gate-delta reset-gate-delta]} gru-delta
        template {:wr-delta             (outer-product unit-delta hidden:t-1)
                  :update-gate-wr-delta (outer-product update-gate-delta hidden:t-1)
                  :reset-gate-wr-delta  (outer-product reset-gate-delta hidden:t-1)
                  :we-delta             (outer-product unit-delta encoder-input)
                  :update-gate-we-delta (outer-product update-gate-delta encoder-input)
                  :reset-gate-we-delta  (outer-product reset-gate-delta encoder-input)
                  :bias-delta unit-delta
                  :update-gate-bias-delta update-gate-delta
                  :reset-gate-bias-delta reset-gate-delta}]
    (if (or (set? x-input) (map? x-input))
      (-> template (assoc :sparses-delta (gru/param-delta-sparse x-input unit-delta update-gate-delta reset-gate-delta)))
      (assoc template
        :w-delta             (outer-product unit-delta x-input)
        :update-gate-w-delta (outer-product update-gate-delta x-input)
        :reset-gate-w-delta  (outer-product reset-gate-delta x-input)))))


(defn encoder-bptt
  [encoder encoder-activation propagated-delta-from-decoder]
  (let [{:keys [hidden hidden-size output]} encoder
        {:keys [wr update-gate-wr reset-gate-wr]} hidden]
    ;looping latest to old
    (loop [propagated-hidden-to-hidden-delta propagated-delta-from-decoder,
           output-seq (reverse encoder-activation),
           hidden-acc nil]
      (if (first output-seq)
        (let [gru-activation (-> output-seq first  :hidden :activation)
              gru-state (-> output-seq first :hidden :state)
              hidden:t-1 (if (second output-seq)
                           (-> output-seq second :hidden :activation :gru)
                           (array :vectorz (repeat hidden-size 0)))
              gru-delta (gru/gru-delta encoder propagated-hidden-to-hidden-delta gru-activation gru-state hidden:t-1 )
              x-input (:input (first output-seq))
              gru-param-delta (gru/gru-param-delta encoder gru-delta x-input hidden:t-1)]
          (recur (:hidden:t-1-delta gru-delta)
                 (rest output-seq)
                 (merge-param! hidden-acc gru-param-delta)))
        {:hidden-delta hidden-acc}))))

(defn decoder-bptt
  [decoder decoder-activation encoder-input output-items-seq]
  (let [{:keys [output-type output hidden encoder-size input-size hidden-size]} decoder
        {:keys [wr update-gate-wr reset-gate-wr
                we update-gate-we reset-gate-we]} hidden]
    ;looping latest to old
    (loop [output-items-seq (reverse output-items-seq),
           propagated-hidden-to-hidden-delta nil,
           output-seq (reverse decoder-activation),
           output-loss [],
           output-acc nil,
           hidden-acc nil,
           encoder-delta (array :vectorz (repeat encoder-size 0))]
      (cond
        (and (= :skip (first output-items-seq)) (nil? propagated-hidden-to-hidden-delta))
        (recur (rest output-items-seq)
               nil
               (rest output-seq)
               output-loss
               nil
               nil
               encoder-delta)
        (first output-seq)
        (let [output-delta (error output-type (:output (:activation (first output-seq))) (first output-items-seq))
              previous-decoder-input (if-let [it (:input (:activation (second output-seq)))] it (array :vectorz (repeat input-size 0)))
              output-param-delta (lstm/decoder-output-param-delta output-delta
                                                                  hidden-size
                                                                  (:gru (:hidden (:activation (first output-seq))))
                                                                  encoder-size
                                                                  encoder-input
                                                                  input-size
                                                                  previous-decoder-input)
              propagated-output-to-hidden-delta (when-not (= :skip (first output-items-seq))
                                                  (->> output-delta
                                                       (map (fn [[item delta]]
                                                              (let [w (:w (get output item))]
                                                                (emul delta w))))
                                                       (apply add!)))
              ;merging delta: hidden-to-hidden + above-to-hidden
              summed-propagated-delta (cond (and (not= :skip (first output-items-seq)) propagated-hidden-to-hidden-delta)
                                            (add! propagated-hidden-to-hidden-delta propagated-output-to-hidden-delta)
                                            (= :skip (first output-items-seq))
                                            propagated-hidden-to-hidden-delta
                                            (nil? propagated-hidden-to-hidden-delta)
                                            propagated-output-to-hidden-delta)
              gru-activation (-> output-seq first :activation :hidden)
              gru-state (-> output-seq first :state :hidden)
              hidden:t-1  (if (second output-seq)
                            (-> output-seq second :activation :hidden :gru)
                            (array :vectorz (repeat hidden-size 0)))
              gru-delta (gru/gru-delta decoder summed-propagated-delta gru-activation gru-state hidden:t-1 )
              x-input (:input (:activation (first output-seq)))
              gru-param-delta (gru-param-delta decoder gru-delta x-input hidden:t-1 encoder-input)
              {:keys [unit-delta update-gate-delta reset-gate-delta]} gru-delta
              propagation-to-encoder (->> (map (fn [w d]
                                                 (mmul (transpose w) d))
                                               [we         update-gate-we    reset-gate-we]
                                               [unit-delta update-gate-delta reset-gate-delta])
                                          (apply add!))]
          (recur (rest output-items-seq)
                 (:hidden:t-1-delta gru-delta)
                 (rest output-seq)
                 (cons output-delta output-loss)
                 (merge-param! output-acc output-param-delta)
                 (merge-param! hidden-acc gru-param-delta)
                 (add! encoder-delta propagation-to-encoder)))
        :else
        {:param-loss {:output-delta output-acc
                      :hidden-delta hidden-acc
                      :encoder-delta encoder-delta}
         :loss output-loss}))))

(defn encoder-decoder-bptt
  [encoder-decoder-model encoder-decoder-forward decoder-output-items-seq]
  (let [{:keys [encoder decoder]} encoder-decoder-model
        {encoder-activation :encoder decoder-activation :decoder} encoder-decoder-forward
        {loss :loss decoder-param-delta :param-loss} (decoder-bptt decoder decoder-activation  (:gru (:activation (:hidden (last encoder-activation)))) decoder-output-items-seq)
        encoder-param-delta (encoder-bptt encoder encoder-activation (:encoder-delta decoder-param-delta))]
    {:loss loss
     :param-loss {:encoder-param-delta encoder-param-delta :decoder-param-delta decoder-param-delta}}))


(defn update-decoder!
  [decoder param-delta-list learning-rate]
  (let [{:keys [output hidden input-size encoder-size optimizer]} decoder
        {:keys [output-delta hidden-delta]} param-delta-list
        {:keys [w-delta wr-delta bias-delta
                update-gate-w-delta update-gate-wr-delta update-gate-bias-delta
                reset-gate-w-delta reset-gate-wr-delta reset-gate-bias-delta
                we-delta update-gate-we-delta reset-gate-we-delta
                sparses-delta]} hidden-delta
        {:keys [w wr bias
                update-gate-w update-gate-wr update-gate-bias
                reset-gate-w reset-gate-wr reset-gate-bias
                we update-gate-we reset-gate-we
                sparse? sparses]} hidden]
    ;update output connection
    (->> output-delta
         (map (fn [[item {:keys [w-delta bias-delta encoder-w-delta previous-input-w-delta previous-input-w-delta]}]]
                (let [{:keys [w bias encoder-w previous-input-w previous-input-sparses]} (get output item)]
                  ; update output params
                  (update-param! optimizer learning-rate bias bias-delta)
                  (update-param! optimizer learning-rate w w-delta)
                  ;; update encoder connection
                  (update-param! optimizer learning-rate encoder-w encoder-w-delta)
                  ;; update decoder previous input
                  ; sparse
                  (->> (:sparses-delta previous-input-w-delta)
                       (map (fn [[item {:keys [w-delta]}]]
                              (let [{:keys [w]} (get previous-input-sparses item)]
                                (update-param! optimizer learning-rate w w-delta))))
                       dorun)
                  ; dense
                  (when (:w previous-input-w-delta) (update-param! optimizer learning-rate previous-input-w (:w previous-input-w-delta))))))
         dorun)
    ;;; update hidden layer
    ;; update input connections
    ; sparse
    (->> sparses-delta
         (map (fn [[word gru-w-delta]]
                (let [{:keys [w-delta update-gate-w-delta reset-gate-w-delta]} gru-w-delta
                      {:keys [w update-gate-w reset-gate-w]} (get sparses word)]
                  (update-param! optimizer learning-rate w w-delta)
                  (update-param! optimizer learning-rate update-gate-w update-gate-w-delta)
                  (update-param! optimizer learning-rate reset-gate-w reset-gate-w-delta))))
         dorun)
    ; dense
    (when w-delta             (update-param! optimizer learning-rate w w-delta))
    (when update-gate-w-delta (update-param! optimizer learning-rate update-gate-w update-gate-w-delta))
    (when reset-gate-w-delta  (update-param! optimizer learning-rate reset-gate-w reset-gate-w-delta))
    ;; update recurrent connections
    (update-param! optimizer learning-rate wr wr-delta)
    (update-param! optimizer learning-rate update-gate-wr update-gate-wr-delta)
    (update-param! optimizer learning-rate reset-gate-wr reset-gate-wr-delta)
    ; update encoder connections
    (update-param! optimizer learning-rate we we-delta)
    (update-param! optimizer learning-rate update-gate-we update-gate-we-delta)
    (update-param! optimizer learning-rate reset-gate-we  reset-gate-we-delta)
    ;; update lstm bias and peephole
    (update-param! optimizer learning-rate bias bias-delta)
    (update-param! optimizer learning-rate update-gate-bias update-gate-bias-delta)
    (update-param! optimizer learning-rate reset-gate-bias reset-gate-bias-delta)
    decoder))

(defn update-encoder-decoder!
  [encoder-decoder-model encoder-decoder-param-delta learning-rate]
  (let[{:keys [encoder decoder]} encoder-decoder-model
       {:keys [encoder-param-delta decoder-param-delta]} encoder-decoder-param-delta]
    (gru/update-model! encoder encoder-param-delta learning-rate)
    (update-decoder!   decoder decoder-param-delta learning-rate)
    encoder-decoder-model))


(defn init-decoder
  [{:keys [input-size input-items output-type output-items encoder-hidden-size decoder-hidden-size]
    :as param}]
  (let [decoder (gru/init-model (assoc param
                                  :input-items input-items
                                  :encoder-size encoder-hidden-size
                                  :hidden-size decoder-hidden-size))
        {:keys [output hidden]} decoder
        d-output (reduce (fn [acc [word param]]
                           (assoc acc word (assoc param
                                             :encoder-w (init-vector encoder-hidden-size)
                                             :previous-input-w (init-vector input-size)
                                             :previous-input-sparses (->> input-items
                                                                          (reduce (fn [acc item]
                                                                                    (assoc acc item {:w (init-vector 1)}))
                                                                                  {})))))
                         {}
                         output)
        d-hidden (assoc hidden ;encoder connection
                   :we       (init-matrix encoder-hidden-size decoder-hidden-size)
                   :update-gate-we  (init-matrix encoder-hidden-size decoder-hidden-size)
                   :reset-gate-we (init-matrix encoder-hidden-size decoder-hidden-size))]
    (assoc decoder
      :hidden d-hidden
      :output d-output
      :input-size input-size
      :hidden-size decoder-hidden-size
      :encoder-size encoder-hidden-size)))

(defn init-encoder-decoder-model
  [{:keys [input-size input-items output-type output-items encoder-hidden-size decoder-hidden-size optimizer]
    :or {optimizer :sgd}
    :as param}]
  (let [encoder (gru/init-model (-> param
                                    (dissoc :output-items)
                                    (assoc
                                      :input-items input-items
                                      :hidden-size encoder-hidden-size
                                      :input-size input-size)))]
    {:encoder encoder
     :decoder (init-decoder param)
     :rnn-type :gru
     :optimizer optimizer}))
