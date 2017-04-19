(ns nn.encoder-decoder-test
  (:require
    [clojure.pprint :refer [pprint]]
    [clojure.test   :refer :all]
    [prism.nn.lstm  :refer [lstm-activation sequential-output]]
    [nn.lstm-test   :refer [sample-w-network]]
    [prism.nn.encoder-decoder :refer :all]))

(def encoder-sample-network
  "assumed 3->5->3 connection"
  {:input-type :dense
   :output-type :binary-classification
   :hidden {:unit-type :lstm
            :unit-num 5
            :block-w (float-array (take 15 (repeat 0.1)))
            :block-wr (float-array (take 25 (repeat 0.1)))
            :block-bias (float-array (take 5 (repeat -1)))
            :input-gate-w  (float-array (take 15 (repeat 0.1)))
            :input-gate-wr  (float-array (take 25 (repeat 0.1)))
            :input-gate-bias (float-array (take 5 (repeat -1)))
            :forget-gate-w (float-array (take 15 (repeat 0.1)))
            :forget-gate-wr (float-array (take 25 (repeat 0.1)))
            :forget-gate-bias (float-array (take 5 (repeat -1)))
            :output-gate-w (float-array (take 15 (repeat 0.1)))
            :output-gate-wr (float-array (take 25 (repeat 0.1)))
            :output-gate-bias (float-array (take 5 (repeat -1)))
            :peephole #{:input-gate :forget-gate :output-gate}
            :input-gate-peephole  (float-array (take 5 (repeat -0.1)))
            :forget-gate-peephole (float-array (take 5 (repeat -0.1)))
            :output-gate-peephole (float-array (take 5 (repeat -0.1)))}
   :output {:activation :sigmoid,
            :layer-type :output,
            :unit-num 3
            :w {"prediction1" (float-array (take 5 (repeat 0.1)))
                "prediction2" (float-array (take 5 (repeat 0.1)))
                "prediction3" (float-array (take 5 (repeat 0.1)))}
            :bias {"prediction1" (float-array [-1])
                   "prediction2" (float-array [-1])
                   "prediction3" (float-array [-1])}}})

(def decoder-sample-network
  "assumed 10 self hidden, 5 encoder connections and embedding-size is 3"
  (let [h (:hidden sample-w-network)]
    (assoc sample-w-network
      :encoder-size 5
      :hidden
      (assoc h
        :block-we
        (float-array (take 50 (repeat 0.02)))
        :input-gate-we
        (float-array (take 50 (repeat 0.02)))
        :forget-gate-we
        (float-array (take 50 (repeat 0.02)))
        :output-gate-we
        (float-array (take 50 (repeat 0.02))))
      :output {"prediction1" {:w (float-array (take 10 (repeat 0.1)))
                              :bias (float-array [-1])
                              :encoder-w (float-array (take 5 (repeat 0.3)))
                              :previous-input-w (float-array (take 3 (repeat 0.25)))}
               "prediction2" {:w (float-array (take 10 (repeat 0.1)))
                              :bias (float-array [-1])
                              :encoder-w (float-array (take 5 (repeat 0.3)))
                              :previous-input-w (float-array (take 3 (repeat 0.25)))}
               "prediction3" {:w (float-array (take 10 (repeat 0.1)))
                              :bias (float-array [-1])
                              :encoder-w (float-array (take 5 (repeat 0.3)))
                              :previous-input-w (float-array (take 3 (repeat 0.25)))}})))

(def sample-encoder-decoder
  {:encoder encoder-sample-network
   :decoder decoder-sample-network})



(deftest encoder-decoder-test
  (testing "init-encoder-decoder-model"
    (let [{:keys [encoder decoder]} (init-encoder-decoder-model {:input-items  nil
                                                                 :output-items #{"A" "B" "C"}
                                                                 :input-type :dense
                                                                 :input-size 3
                                                                 :encoder-hidden-size 10
                                                                 :decoder-hidden-size 20
                                                                 :output-type :binary-classification
                                                                 :embedding {"A" (float-array (map float [1 2 3]))
                                                                             "B" (float-array (map float [1 2 3]))
                                                                             "C" (float-array (map float [1 2 3]))}
                                                                 :embedding-size 3})
          {eh :hidden eis :input-size} encoder
          {dh :hidden dis :input-size o :output es :encoder-size} decoder]
      ;; encoder
      (is (= eis 3))
      (is (= 30  (count (remove zero? (:block-w eh)))))
      (is (= 100 (count (remove zero? (:block-wr eh)))))
      (is (= 30  (count (remove zero? (:input-gate-w eh)))))
      (is (= 100 (count (remove zero? (:input-gate-wr eh)))))
      (is (= 30  (count (remove zero? (:forget-gate-w eh)))))
      (is (= 100 (count (remove zero? (:forget-gate-wr eh)))))
      (is (= 30  (count (remove zero? (:output-gate-w eh)))))
      (is (= 100 (count (remove zero? (:output-gate-wr eh)))))
      (is (= 10  (count (remove zero? (:block-bias eh)))))
      (is (= 10  (count (remove zero? (:input-gate-bias eh)))))
      (is (= 10  (count (remove zero? (:forget-gate-bias eh)))))
      (is (= 10  (count (remove zero? (:output-gate-bias eh)))))
      (is (= 10  (count (remove zero? (:input-gate-peephole eh)))))
      (is (= 10  (count (remove zero? (:forget-gate-peephole eh)))))
      (is (= 10  (count (remove zero? (:output-gate-peephole eh)))))
      ;decoder
      (is (= es 10))
      (is (= dis 3))
      ;; encoder connection
      (is (= 200 (count (remove zero? (:block-we dh)))))
      (is (= 200 (count (remove zero? (:input-gate-we dh)))))
      (is (= 200 (count (remove zero? (:forget-gate-we dh)))))
      (is (= 200 (count (remove zero? (:output-gate-we dh)))))
      ;; same as standard lstm
      (is (= 60  (count (remove zero? (:block-w dh)))))
      (is (= 400 (count (remove zero? (:block-wr dh)))))
      (is (= 60  (count (remove zero? (:input-gate-w dh)))))
      (is (= 400 (count (remove zero? (:input-gate-wr dh)))))
      (is (= 60  (count (remove zero? (:forget-gate-w dh)))))
      (is (= 400 (count (remove zero? (:forget-gate-wr dh)))))
      (is (= 60  (count (remove zero? (:output-gate-w dh)))))
      (is (= 400 (count (remove zero? (:output-gate-wr dh)))))
      (is (= 20  (count (remove zero? (:block-bias dh)))))
      (is (= 20  (count (remove zero? (:input-gate-bias dh)))))
      (is (= 20  (count (remove zero? (:forget-gate-bias dh)))))
      (is (= 20  (count (remove zero? (:output-gate-bias dh)))))
      (is (= 20  (count (remove zero? (:input-gate-peephole dh)))))
      (is (= 20  (count (remove zero? (:forget-gate-peephole dh)))))
      (is (= 20  (count (remove zero? (:output-gate-peephole dh)))))
      ;; decoder output
      (let [{:keys [w bias encoder-w previous-input-w]} (get o "A")]
        (is (= 20 (count w)))
        (is (= 1 (count bias)))
        (is (= 10 (count encoder-w)))
        (is (= 3 (count previous-input-w))))

      ))


  (testing "decoder-lstm-activation"
    (let [{a1 :activation s1 :state} (decoder-lstm-activation decoder-sample-network
                                                              (float-array (take 3 (repeat 2)))
                                                              (float-array (take 10 (repeat 2)))
                                                              (float-array 5)
                                                              (float-array (take 10 (repeat 2))))
          {a2 :activation s2 :state} (lstm-activation sample-w-network
                                                      (float-array (take 3 (repeat 2)))
                                                      (float-array (take 10 (repeat 2)))
                                                      (float-array (take 10 (repeat 2))))]
      (is (= (vec a1)
             (vec a2)
             (take 10 (repeat (float 0.787542)))))
      (is (= (vec (:lstm s1))
             (vec (:lstm s2))
             (take 10 (repeat (float 0.787542)))))
      (is (= (vec (:block s1))
             (vec (:block s2))
             (take 10 (repeat (float 1.5999999)))))
      (is (= (vec (:input-gate s1))
             (vec (:input-gate s2))
             (take 10 (repeat (float 1.3999999)))))
      (is (= (vec (:forget-gate s1))
             (vec (:forget-gate s2))
             (take 10 (repeat (float 1.3999999)))))
      (is (= (vec (:output-gate s1))
             (vec (:output-gate s2))
             (take 10 (repeat (float 1.3999999)))))
      (is (= (vec (:cell-state  s1))
             (vec (:cell-state  s2))
             (take 10 (repeat (float 2.3437154))))))
    (let [{a :activation s :state} (decoder-lstm-activation decoder-sample-network
                                                            (float-array (take 3 (repeat 2)))
                                                            (float-array (take 10 (repeat 2)))
                                                            (float-array (take 5 (repeat 3)))
                                                            (float-array (take 10 (repeat 2))))]
      (is (= (vec a) (take 10 (repeat (float 0.83420765)))))
      (is (= (vec (:lstm s)) (take 10 (repeat (float 0.83420765)))))
      (is (= (vec (:block s)) (take 10 (repeat (float 1.8999999)))))
      (is (= (vec (:input-gate s)) (take 10 (repeat (float 1.6999998)))))
      (is (= (vec (:forget-gate s)) (take 10 (repeat (float 1.6999998)))))
      (is (= (vec (:output-gate s)) (take 10 (repeat (float 1.6999998)))))))
  (testing "encoder-forward"
    (let [result (encoder-forward sample-w-network (map float-array [[1 0 0] [1 0 0]]))
          {:keys [hidden input]} (last result)
          {:keys [block input-gate forget-gate output-gate]} (:state hidden)]
      (is (= (vec input) (map float [1 0 0])))
      (is (= 2 (count result)))
      (is (= (vec block) (take 10 (repeat (float -0.9590061)))))
      (is (= (vec input-gate) (take 10 (repeat (float -0.93830144)))))
      (is (= (vec forget-gate) (take 10 (repeat (float -0.93830144)))))
      (is (= (vec output-gate) (take 10 (repeat (float -0.93830144)))))))

  (testing "decoder-output-activation"
    (is (= (decoder-output-activation decoder-sample-network
                                      (float-array (take 10 (repeat (float 0.05))))
                                      (float-array (take 5 (repeat (float 0.1))))
                                      (float-array (map float [0.2 0.2 0.1]))
                                      #{"prediction1"})
           {"prediction1" (float 0.33737817)})))

  (testing "decoder-activation-time-fixed"
    (let [{:keys [activation state]} (decoder-activation-time-fixed decoder-sample-network
                                                                    (float-array (take 3 (repeat (float 0.3))))
                                                                    #{"prediction1"}
                                                                    (float-array (take 10 (repeat (float 0.1))))
                                                                    (float-array (take 5 (repeat (float 0.1))))
                                                                    (float-array (map float [0.2 0.2 0.1]))
                                                                    (float-array (take 10 (repeat (float 0.25)))))
          {hs :hidden} state]
      (is (= (vec (:input activation))
             (take 3 (repeat (float 0.3)))))
      (is (= (vec (:hidden  activation))
             (take 10 (repeat (float -0.038238235)))))
      (is (= (vec (:block hs))
             (take 10 (repeat (float -0.79999995)))))
      (is (= (vec (:input-gate hs))
             (take 10 (repeat (float -0.8249999 )))))
      (is (= (vec (:forget-gate hs))
             (take 10 (repeat (float -0.8249999 )))))
      (is (= (vec (:output-gate hs))
             (take 10 (repeat (float -0.8249999 )))))))

  (testing "decorder-forward"
    (let [it1 (vec (:output (:activation (last (decoder-forward decoder-sample-network
                                                                (map float-array [[2 0 0] [1 0 0]])
                                                                (float-array 5)
                                                                [:skip #{"prediction1" "prediction2" "prediction3"}])))))
          it2 (vec (:output (:activation (last (sequential-output sample-w-network
                                                                  (map float-array [[2 0 0] [1 0 0]])
                                                                  [:skip #{"prediction1" "prediction2" "prediction3"}])))))]
      (is (not= it1 it2))
      (is (= (->> it1 (reduce (fn [acc [i x]] (assoc acc i (float x))) {}))
             {"prediction2" (float 0.36052307) "prediction1" (float 0.36052307) "prediction3" (float 0.36052307)})))
    (let [result (decoder-forward decoder-sample-network
                                  (map float-array [[2 0 0] [1 0 0]])
                                  (float-array (take 5 (repeat (float -0.1))))
                                  [:skip #{"prediction1" "prediction2" "prediction3"}])
          {:keys [activation state]} (last result)
          {:keys [hidden output]} activation]
      (is (= (vec hidden) (take 10 (repeat (float -0.07243964)))))
      (is (= (->> output vec (reduce (fn [acc [i x]] (assoc acc i (float x))) {}))
             {"prediction2" (float 0.326856) "prediction1" (float 0.326856) "prediction3" (float 0.326856)}))))

  (testing "encoder-decoder-forward"
    (let [{:keys [encoder decoder]} (encoder-decoder-forward sample-encoder-decoder
                                                             (map float-array [[2 0 0] [0 -1 1]])
                                                             (map float-array [[-1 1 -1] [2 -1 1]])
                                                             [#{"prediction1" "prediction2"} #{"prediction2" "prediction3"}])
          {:keys [hidden output]} (:activation (last decoder))]
      (is (= (vec (:activation (:hidden (last encoder))))
             (take 5 (repeat (float -0.06823918)))))
      (is (= (vec hidden) (take 10 (repeat (float -0.07979079)))))
      (is (= (->> output vec (reduce (fn [acc [i x]] (assoc acc i (float x))) {}))
             {"prediction2" (float 0.19276398) "prediction3" (float 0.19276398)}))))



  ;;   BPTT   ;;



  (testing "decoder-output-param-delta"
    (let [result (->> (decoder-output-param-delta {"A" 0.5 "B" 0 "C" -0.5}
                                                  10
                                                  (float-array (range 10))
                                                  5
                                                  (float-array (take 5 (repeat (float 0.1))))
                                                  3
                                                  (float-array (take 3 (repeat (float -0.1)))))
                      (reduce (fn [acc [item {:keys [w-delta bias-delta encoder-w-delta previous-input-w-delta]}]]
                                (assoc acc item {:w-delta (mapv float w-delta)
                                                 :bias-delta (map float bias-delta)
                                                 :encoder-w-delta (map float encoder-w-delta)
                                                 :previous-input-w-delta (map float previous-input-w-delta)}))
                              {}))
          {:strs [A B C]} result]
      (is (= A {:w-delta (map float [0.0 0.5 1.0 1.5 2.0 2.5 3.0 3.5 4.0 4.5])
                :bias-delta [(float 0.5)]
                :encoder-w-delta (take 5 (repeat (float 0.05)))
                :previous-input-w-delta (take 3 (repeat (float -0.05)))}))
      (is (= B {:w-delta (take 10 (repeat (float 0)))
                :bias-delta [(float 0)]
                :encoder-w-delta (take 5 (repeat (float 0)))
                :previous-input-w-delta (take 3 (repeat (float 0)))}))
      (is (= C {:w-delta (map float [-0.0 -0.5 -1.0 -1.5 -2.0 -2.5 -3.0 -3.5 -4.0 -4.5])
                :bias-delta [(float -0.5)]
                :encoder-w-delta (take 5 (repeat (float -0.05)))
                :previous-input-w-delta (take 3 (repeat (float 0.05)))}))))
  (testing "decoder-lstm-param-delta"
    (let [result (decoder-lstm-param-delta decoder-sample-network
                                           {:block-delta       (float-array (take 10 (repeat 1)))
                                            :input-gate-delta  (float-array (take 10 (repeat 1)))
                                            :forget-gate-delta (float-array (take 10 (repeat 1)))
                                            :output-gate-delta (float-array (take 10 (repeat 1)))}
                                           (float-array [2 1 -1])
                                           (float-array (take 10 (repeat (float 0.2))))
                                           (float-array (take 5 (repeat (float 0.02))))
                                           {:cell-state (float-array (take 10 (repeat (float -0.1))))})]
      (is (= (vec (:block-w-delta result))  (take 30 (flatten (repeat (map float [2.0 1.0 -1.0]))))))
      (is (= (vec (:block-wr-delta result)) (take 100 (repeat (float 0.2)))))
      (is (= (vec (:input-gate-w-delta result)) (take 30 (flatten (repeat (map float [2.0 1.0 -1.0]))))))
      (is (= (vec (:input-gate-wr-delta result))  (take 100 (repeat (float 0.2)))))
      (is (= (vec (:forget-gate-w-delta result)) (take 30 (flatten (repeat (map float [2.0 1.0 -1.0]))))))
      (is (= (vec (:forget-gate-wr-delta result)) (take 100 (repeat (float 0.2)))))
      (is (= (vec (:output-gate-w-delta result))  (take 30 (flatten (repeat (map float [2.0 1.0 -1.0]))))))
      (is (= (vec (:output-gate-wr-delta result)) (take 100 (repeat (float 0.2)))))
      ;; encoder connection
      (is (= (vec (:block-we-delta result)) (take 50 (repeat (float 0.02)))))
      (is (= (vec (:input-gate-we-delta result)) (take 50 (repeat (float 0.02)))))
      (is (= (vec (:forget-gate-we-delta result)) (take 50 (repeat (float 0.02)))))
      (is (= (vec (:output-gate-we-delta result)) (take 50 (repeat (float 0.02)))))
      ;; bias and peppholes
      (is (= (vec (:block-bias-delta result)) (take 10 (repeat (float 1)))))
      (is (= (vec (:input-gate-bias-delta result)) (take 10 (repeat (float 1)))))
      (is (= (vec (:forget-gate-bias-delta result)) (take 10 (repeat (float 1)))))
      (is (= (vec (:output-gate-bias-delta result)) (take 10 (repeat (float 1)))))
      (is (= (vec (:peephole-input-gate-delta  result)) (take 10 (repeat (float -0.1)))))
      (is (= (vec (:peephole-forget-gate-delta result)) (take 10 (repeat (float -0.1)))))
      (is (= (vec (:peephole-output-gate-delta result)) (take 10 (repeat (float -0.1)))))))
  (testing "encoder-bptt"
    (let [hd (:hidden-delta (encoder-bptt encoder-sample-network
                                          (encoder-forward encoder-sample-network (map float-array [[1 0 0] [1 0 0]]))
                                          (float-array (take 5 (repeat (float -0.5))))))]
      (is (= (count (:block-w-delta                 hd)) 15))
      (is (= (count (remove zero? (:block-w-delta   hd))) 5))
      (is (= (count (remove zero? (:block-wr-delta  hd))) 25))
      (is (= (count (:input-gate-w-delta            hd)) 15))
      (is (= (count (remove zero? (:input-gate-w-delta   hd))) 5))
      (is (= (count (remove zero? (:input-gate-wr-delta  hd))) 25))
      (is (= (count (:forget-gate-w-delta           hd)) 15))
      (is (= (count (remove zero? (:forget-gate-w-delta   hd))) 5))
      (is (= (count (:forget-gate-wr-delta          hd)) 25))
      (is (= (count (remove zero? (:forget-gate-wr-delta  hd))) 25))
      (is (= (count (:output-gate-w-delta           hd)) 15))
      (is (= (count (remove zero? (:output-gate-w-delta   hd))) 5))
      (is (= (count (remove zero? (:output-gate-wr-delta  hd))) 25))
      ;; bias and peephole
      (is (= (count (remove zero? (:block-bias-delta           hd))) 5))
      (is (= (count (remove zero? (:input-gate-bias-delta      hd))) 5))
      (is (= (count (remove zero? (:forget-gate-bias-delta     hd))) 5))
      (is (= (count (remove zero? (:output-gate-bias-delta     hd))) 5))
      (is (= (count (remove zero? (:peephole-input-gate-delta  hd))) 5))
      (is (= (count (remove zero? (:peephole-forget-gate-delta hd))) 5))
      (is (= (count (remove zero? (:peephole-output-gate-delta hd))) 5))))
  (testing "decoder-bptt"
    (let [encoder-input (float-array (take 5 (repeat (float -0.1))))
          {hd :hidden-delta od :output-delta ed :encoder-delta}
          (decoder-bptt decoder-sample-network
                        (decoder-forward decoder-sample-network
                                         (map float-array [[2 0 0] [1 0 0]])
                                         encoder-input
                                         [:skip #{"prediction1" "prediction2" "prediction3"}])
                        encoder-input
                        [:skip {:pos ["prediction2"] :neg ["prediction3"]}])
          {:keys [w-delta bias-delta encoder-w-delta previous-input-w-delta]} (get od "prediction2")]
      (is (= (count (:block-w-delta                 hd)) 30))
      (is (= (count (remove zero? (:block-w-delta   hd))) 10))
      (is (= (count (remove zero? (:block-wr-delta  hd))) 100))
      (is (= (count (:input-gate-w-delta            hd)) 30))
      (is (= (count (remove zero? (:input-gate-w-delta   hd))) 10))
      (is (= (count (remove zero? (:input-gate-wr-delta  hd))) 100))
      (is (= (count (:forget-gate-w-delta           hd)) 30))
      (is (= (count (remove zero? (:forget-gate-w-delta   hd))) 10))
      (is (= (count (:forget-gate-wr-delta          hd)) 100))
      (is (= (count (remove zero? (:forget-gate-wr-delta  hd))) 100))
      (is (= (count (:output-gate-w-delta           hd)) 30))
      (is (= (count (remove zero? (:output-gate-w-delta   hd))) 10))
      (is (= (count (remove zero? (:output-gate-wr-delta  hd))) 100))
      ;; encoder-connection
      (is (= (count (remove zero? (:block-we-delta  hd))) 50))
      (is (= (count (remove zero? (:input-gate-we-delta  hd))) 50))
      (is (= (count (:forget-gate-we-delta          hd)) 50))
      (is (= (count (remove zero? (:output-gate-we-delta  hd))) 50))
      ;; bias and peepholes
      (is (= (count (:block-bias-delta           hd)) 10))
      (is (= (count (:input-gate-bias-delta      hd)) 10))
      (is (= (count (:forget-gate-bias-delta     hd)) 10))
      (is (= (count (:output-gate-bias-delta     hd)) 10))
      (is (= (count (:peephole-input-gate-delta  hd)) 10))
      (is (= (count (:peephole-forget-gate-delta hd)) 10))
      (is (= (count (:peephole-output-gate-delta hd)) 10))
      ;; output
      (is (= (vec w-delta)
             (take 10 (repeat (float -0.048762307)))))
      (is (= (vec bias-delta) [(float 0.673144)]))
      (is (= (vec encoder-w-delta)
             (take 5 (repeat (float -0.0673144)))))
      (is (= (vec previous-input-w-delta)
             (map float [1.346288 0.0 0.0])))
      ;; encoder-delta
      (is (= (vec ed)
             (take 5 (repeat (float -4.5863548E-4)))))))

  (testing "encoder-decoder-bptt"
    (let [{:keys [encoder-param-delta decoder-param-delta]}
          (encoder-decoder-bptt sample-encoder-decoder
                                (encoder-decoder-forward sample-encoder-decoder
                                                         (map float-array [[2 0 0] [0 -1 1]])
                                                         (map float-array [[-1 1 -1] [2 -1 1]])
                                                         [#{"prediction1" "prediction2"} #{"prediction2" "prediction3"}])
                                [{:pos ["prediction1"] :neg ["prediction2"]} {:pos ["prediction2"] :neg ["prediction3"]}])]
      (is (not (nil? encoder-param-delta)))
      (is (not (nil? decoder-param-delta)))))

  (testing "update-decoder!"
    (let [encoder-input (float-array (take 5 (repeat (float -0.1))))
          result (update-decoder! decoder-sample-network
                                  (decoder-bptt decoder-sample-network
                                                (decoder-forward decoder-sample-network
                                                                 (map float-array [[2 0 0] [1 0 0]])
                                                                 encoder-input
                                                                 [:skip #{"prediction1" "prediction2" "prediction3"}])
                                                encoder-input
                                                [:skip {:pos ["prediction2"] :neg ["prediction3"]}])
                                  0.1)
          {hd :hidden o :output} result]
      (is (= (count (:block-w hd)) 30))
      (is (= (count (:block-wr hd)) 100))
      (is (= (count (:input-gate-w hd)) 30))
      (is (= (count (:input-gate-wr hd)) 100))
      (is (= (count (:forget-gate-w hd)) 30))
      (is (= (count (:forget-gate-wr hd)) 100))
      (is (= (count (:output-gate-w hd)) 30))
      (is (= (count (:output-gate-wr hd)) 100))

      (is (= (vec (:block-w hd))
             (cons (float 0.100179605) (flatten (take 29 (repeat (float 0.1)))))))
      (is (= (vec (:input-gate-w hd))
             (cons (float 0.099804856) (flatten (take 29 (repeat (float 0.1)))))))
      (is (= (vec (:forget-gate-w hd))
             (cons (float 0.099962) (flatten (take 29 (repeat (float 0.1)))))))
      (is (= (vec (:output-gate-w hd))
             (cons (float 0.09984027) (flatten (take 29 (repeat (map float [0.1])))))))
      ;; reccurent connection
      (is (= (vec (:block-wr hd))
             (take 100 (repeat (float 0.099993005)))))
      (is (= (vec (:input-gate-wr hd))
             (take 100 (repeat (float 0.10000865)))))
      (is (= (vec (:forget-gate-wr hd))
             (take 100 (repeat (float 0.10000238)))))
      (is (= (vec (:output-gate-wr hd))
             (take 100 (repeat (float 0.10001133)))))
      ;; encoder connection
      (is (= (vec (:block-we hd))
             (concat (take 5 (repeat (float 0.019985428)))
                     (take 45 (repeat (float 0.02))))))
      (is (= (vec (:input-gate-we hd))
             (concat (take 5 (repeat (float 0.020016667)))
                     (take 45 (repeat (float 0.02))))))
      (is (= (vec (:forget-gate-we hd))
             (concat (take 5 (repeat (float 0.0200038)))
                     (take 45 (repeat (float 0.02))))))
      (is (= (vec (:output-gate-we hd))
             (concat (take 5 (repeat (float 0.020017035)))
                     (take 45 (repeat (float 0.02))))))
      ;peephole
      (is (= (vec (:input-gate-peephole hd))
             (take 10 (repeat (float -0.09997151)))))
      (is (= (vec (:forget-gate-peephole hd))
             (take 10 (repeat (float -0.09999217)))))
      (is (= (vec (:output-gate-peephole hd))
             (take 10 (repeat (float -0.09996269)))))

      (is (= (vec (:input-gate-bias  hd)) (take 10 (repeat (float -1.0001667)))))
      (is (= (vec (:forget-gate-bias hd)) (take 10 (repeat (float -1.000038)))))
      (is (= (vec (:output-gate-bias hd)) (take 10 (repeat (float -1.0001704)))))
      (let [{:keys [w bias encoder-w previous-input-w]} (get o "prediction3")]
        (is (= (vec w)
               (take 10 (repeat (float 0.10236774)))))
        (is (= (vec bias)
               [(float -1.0326856)]))
        (is (= (vec encoder-w)
               (take 5 (repeat (float 0.30326858)))))
        (is (= (vec previous-input-w)
               (map float [0.1846288 0.25 0.25]))))))
  (testing "update-encoder-decoder!"
    (let [encoder-input (float-array (take 5 (repeat (float -0.1))))]
      (update-encoder-decoder! sample-encoder-decoder
                               (encoder-decoder-bptt sample-encoder-decoder
                                                     (encoder-decoder-forward sample-encoder-decoder
                                                                              (map float-array [[2 0 0] [0 -1 1]])
                                                                              (map float-array [[-1 1 -1] [2 -1 1]])
                                                                              [#{"prediction1" "prediction2"} #{"prediction2" "prediction3"}])
                                                     [{:pos ["prediction1"] :neg ["prediction2"]} {:pos ["prediction2"] :neg ["prediction3"]}])
                               0.01)
      (let [{:keys [encoder decoder]} sample-encoder-decoder
            {eh :hidden __ :output} encoder
            {dh :hidden do :output} decoder]
        ;; encoder
        (is (= (count (:block-w eh)) 15))
        (is (= (count (:block-wr eh)) 25))
        (is (= (count (:input-gate-w eh)) 15))
        (is (= (count (:input-gate-wr eh)) 25))
        (is (= (count (:forget-gate-w eh)) 15))
        (is (= (count (:forget-gate-wr eh)) 25))
        (is (= (count (:output-gate-w eh)) 15))
        (is (= (count (:output-gate-wr eh)) 25))

        ;; Caution! decoder's parameter already have updated by above test.
        (is (= (count (:block-w dh)) 30))
        (is (= (count (:block-wr dh)) 100))
        (is (= (count (:input-gate-w dh)) 30))
        (is (= (count (:input-gate-wr dh)) 100))
        (is (= (count (:forget-gate-w dh)) 30))
        (is (= (count (:forget-gate-wr dh)) 100))
        (is (= (count (:output-gate-w dh)) 30))
        (is (= (count (:output-gate-wr dh)) 100))
        ;; encoder connection
        (is (= (vec (:block-we dh))
               (concat (take 5 (repeat (float 0.019982642)))
                       (take 45 (repeat (float 0.02))))))
        (is (= (vec (:input-gate-we dh))
               (concat (take 5 (repeat (float 0.020020049)))
                       (take 45 (repeat (float 0.02))))))
        (is (= (vec (:forget-gate-we dh))
               (concat (take 5 (repeat (float 0.020004272)))
                       (take 45 (repeat (float 0.02))))))
        (is (= (vec (:output-gate-we dh))
               (concat (take 5 (repeat (float 0.020020423)))
                       (take 45 (repeat (float 0.02))))))
        ;; decoder output
        (let [{:keys [w bias encoder-w previous-input-w]} (get do "prediction1")]
          (is (= 10 (count w)))
          (is (= 1 (count bias)))
          (is (= 5 (count encoder-w)))
          (is (= 3 (count previous-input-w))))))))






