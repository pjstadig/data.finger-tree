;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns ^{:doc "Tests for finger tree collections."
      :author "Chris Houser"}
  clojure.data.finger-tree.tests
  (:use [clojure.test :only [deftest is are]]
        [clojure.data.finger-tree
         :only [finger-tree meter consl conjr ft-concat ft-split-at split-tree
                opfn idElem measure measured to-tree getMeter
                double-list counted-double-list counted-sorted-set]])
  (:import (clojure.data.finger-tree CountedSortedSet CountedDoubleList)))

(deftest Conj-Seq-Queue
  (let [len 100]
    (are [x] (= (map identity x) (range len))
      (rseq (reduce consl (double-list) (range len)))
      (seq  (reduce conjr (double-list) (range len))))))

(deftest Conj-Seq-Stack
  (let [len 100]
    (are [x] (= (map identity x) (range (dec len) -1 -1))
      (rseq (reduce conjr (double-list) (range len)))
      (seq  (reduce consl (double-list) (range len))))))
    
(deftest Conj-Seq-Mixed
  (doseq [m (range 2 7)]
    (loop [ft (double-list), vc [], i (int 0)]
      (when (< i 40)
        (is (= (seq (map identity ft)) (seq vc)))
        (if (zero? (rem i m))
          (recur (consl ft i) (vec (cons i vc)) (inc i))
          (recur (conjr ft i) (conj vc i)       (inc i)))))))

(deftest Concat
  (doseq [a-len (range 25), b-len (range 25)]
    (let [a-s (map #(symbol (str % 'a)) (range a-len))
          b-s (map #(symbol (str % 'b)) (range b-len))
          a (apply double-list a-s)
          b (apply double-list b-s)]
      (is (= (seq (concat a-s b-s)) (seq (map identity (ft-concat a b))))))))

(defn test-split-at [expected-vec counted-tree tree-type]
  (dotimes [n (count expected-vec)]
    (let [[l m r] (ft-split-at counted-tree n)]
      (is (instance? tree-type l))
      (is (instance? tree-type r))
      (is (= (nth expected-vec n) m))
      (is (= n (count l)))
      (is (= (- (count expected-vec) n 1) (count r)))
      (is (= (subvec expected-vec 0 n) l))
      (is (= (subvec expected-vec (inc n)) r))))
  
  (let [[l m r] (ft-split-at counted-tree -1)]
    (is (instance? tree-type l))
    (is (instance? tree-type r))
    (is (nil? m))
    (is (zero? (count l)))
    (is (= (count expected-vec) (count r)))
    (is (empty? l))
    (is (= expected-vec r)))

  (let [len (count expected-vec)
        [l m r] (ft-split-at counted-tree len)]
    (is (instance? tree-type l))
    (is (instance? tree-type r))
    (is (nil? m))
    (is (= len (count l)))
    (is (zero? (count r)))
    (is (= expected-vec l))
    (is (empty? r))))

(deftest CDLSplit
  (let [basevec (vec (map #(format "x%02d" %) (range 50)))]
    (dotimes [len (count basevec)]
      (let [lenvec (subvec basevec 0 len)]
        (test-split-at lenvec (apply counted-double-list lenvec)
                       CountedDoubleList)))))

(deftest CDLAssoc
  (doseq [len (range 50), n (range (inc len))]
    (let [v (assoc (vec (range len)) n :x)
          cdl (assoc (apply counted-double-list (range len)) n :x)]
      (is (= v cdl))
      (doseq [i (range len)]
        (is (= (nth v i) (nth cdl i)))
        (is (= (get v i) (get cdl i))))
      (doseq [i [-1 len]]
        (is (= (nth v i :nf) (nth cdl i :nf)))
        (is (= (get v i :nf) (get cdl i :nf)))))))

(deftest CDLAssocCons
  (doseq [len (range 50)]
    (is (= (vec (cons :x (range len)))
           (assoc (apply counted-double-list (range len)) -1 :x)))))

(deftest CDLAssocFail
  (doseq [len (range 50), n [-2 (inc len)]]
    (is (thrown? Exception
                 (assoc (apply counted-double-list (range len)) n :x)))))

(deftest CSSConjDisj
  (let [values (vec (concat (range 50) [4.5 10.5 45.5 30.5]))]
    (dotimes [len (count values)]
      (let [pset (apply sorted-set (take len values))
            base (apply counted-sorted-set (take len values))] ; cons
        (is (= len (count base)))                    ; counted
        (dotimes [n len]
          (is (= (seq pset) (conj base (values n)))) ; exclusive set, next
          (is (= (nth (seq pset) n) (nth base n)))   ; indexed lookup
          (is (= (values n) (get base (values n))))) ; set lookup
        (reduce (fn [[pset base] value]              ; disj
                  (is (= (seq pset) base))
                  (is (= (count pset) (count base)))
                  [(disj pset value) (disj base value)])
                [pset base] (take len values))))))

(deftest CSSSplitAt
  (let [basevec (vec (map #(format "x%02d" %) (range 50)))]
    (dotimes [len (count basevec)]
      (let [lenvec (subvec basevec 0 len)]
        (test-split-at lenvec (apply counted-sorted-set lenvec)
                       CountedSortedSet)))))

(deftest CSSPeekPop
  (let [basevec (vec (map #(format "x%02d" %) (range 50)))]
    (loop [v basevec, t (apply counted-sorted-set basevec)]
      (is (= (peek v) (peek t)))
      (is (= v t))
      (when (seq v)
        (recur (pop v) (pop t))))))

; for CSS: subseq, rsubseq

(defrecord Len-Meter [^int len])
(def measure-len (constantly (Len-Meter. 1)))
(def len-meter (meter measure-len
                      (Len-Meter. 0)
                      #(Len-Meter. (+ (:len %1) (:len %2)))))

(defrecord String-Meter [string])
(defn ^:static measure-str [node] (String-Meter. (str node)))
(def string-meter (meter measure-str
                         (String-Meter. "")
                         #(String-Meter. (str (:string %1) (:string %2)))))


(defrecord Len-String-Meter [len string])

(def len-string-meter
  (let [len-op (opfn len-meter)
        string-op (opfn string-meter)]
    (meter
      (fn [o]
        (Len-String-Meter. (:len (measure len-meter o))
                           (:string (measure string-meter o))))
      (Len-String-Meter. (:len (idElem len-meter))
                         (:string (idElem string-meter)))
      (fn [a b] (Len-String-Meter.
                  (:len (len-op a b))
                  (:string (string-op a b)))))))

(deftest Annotate-One-Direction
  (let [measure-fns len-string-meter]
    (let [len 100]
      (are [x] (= x (Len-String-Meter. len (apply str (range len))))
        (measured (reduce conjr (finger-tree measure-fns) (range len))))
      (are [x] (= x (Len-String-Meter. len (apply str (reverse (range len)))))
        (measured (reduce consl (finger-tree measure-fns) (range len)))))))
      
(deftest Annotate-Mixed-Conj
  (let [measure-fns len-string-meter]
    (doseq [m (range 2 7)]
      (loop [ft (finger-tree measure-fns), vc [], i (int 0)]
        (when (< i 40)
          (is (= (measured ft) (Len-String-Meter. (count vc) (apply str vc))))
          (if (zero? (rem i m))
            (recur (consl ft i) (vec (cons i vc)) (inc i))
            (recur (conjr ft i) (conj vc i)       (inc i))))))))

(deftest Ann-Conj-Seq-Queue
  (let [len 100]
    (are [x] (= (map identity x) (range len))
      (rseq (reduce consl (counted-double-list) (range len)))
      (seq  (reduce conjr (counted-double-list) (range len))))))

(deftest Counted-Test
  (let [xs (map #(str "x" %) (range 1000))
        cdl (apply counted-double-list xs)]
    (is (= (concat [nil] xs [nil]) (map #(get cdl %) (range -1 1001))))))

(deftest Annotate-Concat
  (let [measure-fns len-string-meter]
    (doseq [a-len (range 25), b-len (range 25)]
      (let [a-s (map #(symbol (str % 'a)) (range a-len))
            b-s (map #(symbol (str % 'b)) (range b-len))
            a (apply finger-tree measure-fns a-s)
            b (apply finger-tree measure-fns b-s)]
        (is (= (Len-String-Meter.
                 (+ (count a-s) (count b-s))
                 (apply str (concat a-s b-s)))
               (measured (ft-concat a b))))))))

(deftest Split
  (let [make-item (fn [i] (symbol (str i 'a)))]
    (doseq [len (range 10)
            :let [tree (to-tree len-string-meter (map make-item (range len)))]
            split-i (range len)]
      (is (= [len split-i (make-item split-i)]
             [len split-i (second (split-tree tree #(< split-i (:len %))))])))))

(defrecord Right-Meter [right])
(defn measure-right [x] (Right-Meter. x))
(def zero-right (Right-Meter. nil))
(def right-meter
  (meter measure-right
         zero-right
         #(if (:right %2) %2 %1)))

(defn insert-where [tree pred value]
  (if (empty? tree)
    (conjr tree value)
    (let [[l x r] (split-tree tree pred)
          [a b] (if (pred (measure (getMeter tree) x)) [value x] [x value])]
      (ft-concat (conjr l a) (consl r b)))))
  

(deftest Sorted-Set
  (let [r (java.util.Random. 42)]
    (reduce (fn [[t s] i]
              (let [t2 (insert-where t
                                     #(when-let [r (:right %)] (< i r))
                                     i)
                    s (conj s i)]
                (is (= (seq s) t2))
                [t2 s]))
            [(finger-tree right-meter) (sorted-set)]
            (take 1000 (repeatedly #(.nextInt r))))))

