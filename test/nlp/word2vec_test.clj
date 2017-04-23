(ns nlp.word2vec-test
  (:require [clojure.test :refer :all]
            [clojure.string :refer [split]]
            [clojure.pprint :refer [pprint]]
            [prism.util :refer [make-wc similarity]]
            [prism.nlp.word2vec :refer :all]))

(deftest word2ec-test
  (testing "subsampling"
    (is (= (subsampling "A" 0.001 1.0e-3)
           "A")))
  (testing "sg-windows"
    (is (= (count (sg-windows ["A" "B" "C" "D" "E" "F" "G"] 3))
           7)))
  (testing "skip-gram-training-pair"
    (let [coll (skip-gram-training-pair {"A" 1 "B" 20 "C" 30 "common word" 10000 "D" 10 "E" 10} 10071 ["A" "B" "common word" "C" "D" "E"]
                                        {:window-size 2})]
      (is (not (true? (->> coll flatten (some #(or (= % "<bos>") (= % "<eos>")))))))
      (is (= 1 (count (ffirst coll))))
      (is (not (zero? (count (second (first coll))))))))
  (testing "init-w2v-model"
    (let [{:keys [hidden wc input-type output-type]} (init-w2v-model {"A" 12 "B" 345 "C" 42} 10)
          {:keys [unit-num]} hidden]
      (is (= unit-num 10))
      (is (= input-type :sparse))
      (is (= output-type :binary-classification))
      (is (= (count (keys wc)) 3))))
  (let [target-tok "test/nlp/example.tok"
        wc (make-wc target-tok {:interval-ms 1000 :workers 1 :min-count 1})
        w2v (init-w2v-model wc 10)
        A (aclone (get-in w2v [:hidden :w "A"]))
        D (aclone (get-in w2v [:hidden :w "D"]))
        d (aclone (get-in w2v [:hidden :w "d"]))
        X (aclone (get-in w2v [:hidden :w "X"]))]
    (testing "train-word2vec!"
      (dotimes [_ 5] (train-word2vec! w2v target-tok {:min-learning-rate 0.025 :nagetaive 3 :workers 1 :interval-ms 2000 :sample 0.1}))
      (is (not= (vec D) (vec (get-in w2v [:hidden :w "D"])))))
    (testing "learned word embedding"
      (is (< (similarity A D false) (similarity D d false)))
      (is (< (similarity A D false) (similarity X d false))))))
