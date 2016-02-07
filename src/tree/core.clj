(ns tree.core
  (:refer-clojure :exclude [compare resolve subvec])
  (:require [clojure.core.rrb-vector :refer (catvec subvec)]))

(def b 3)

(defprotocol IKeyCompare
  (compare [key1 key2]))

;; TODO merge w/ IInsert?
(defprotocol INodeLookup
  (lookup [node key] "Returns the child node which contains the given key"))

;;TODO rename to something other than IInsert
(defprotocol IInsert
  (insert [node index new-child]
          [node index new-child1 median new-child2]
          "Returns one or 2 new nodes, if it had to split")
  (underflow [node parent] "returns the ") ;;TODO 
  )
;; We'll unify deleting keys from leaves and from indexed blocks by working on indices
;; Given a node, we'll remove a target index from it. If that doesn't cause it to underflow, it returns the modified node. If it does cause it to underflow, it can also see its siblings, with whom it can merge, then potentially split. If it did a merge and split (aka steal), then we simply rewrite the 2 children in the parent's index, and update
;; Rewrite requirements:
;; If no underflow occured, simply updates itself
;; If some underflow occurred, it could steal from the left or right sibling.
;; Can be called with some number of siblings

(defprotocol IResolve
  "This is how we store the children. The indirection enables background
   fetch and decode of the resource."
  (resolve [this]))

(extend-protocol IKeyCompare
  ;; By default, we use the default comparator
  Object
  (compare [key1 key2] (clojure.core/compare key1 key2)))

;;TODO use Collections/binarySearch, requires us to use a more standard comparator
(defn scan-children-array
  "This function takes an array of keys. There must be an odd # of elts in it.

   It returns the last index which is less than to the given key,
   unless no such index exists, in which case it returns the greatest index"
  [keys key]
  (let [key-len (count keys)]
    (loop [i 0]
      (if (> key-len i) ;; Are there more elements?
        (let [result (compare (nth keys i) key)]
          (cond
            (neg? result) ;; If current key is smaller, keep scanning
            (recur (inc i))
            (or (zero? result) (pos? result))
            i
            :else
            (throw (ex-info "lol" {:no :darn}))))
        ;; All keys are smaller
        key-len))))

;; TODO enforce that there always (= (count children) (inc (count keys)))
;;
;; TODO we should be able to find all uncommited data by searching for
;; resolved & unresolved children

(defn handle-multi-insert
  [factory {:keys [keys children] :as node} index new-child1 median new-child2]
  (let [new-children (catvec (conj (subvec children 0 index)
                                   new-child1 new-child2)
                             (subvec children (inc index)))
        new-keys (catvec (conj (subvec keys 0 index)
                               median)
                         (subvec keys index))]
    (if (>= (count new-children) (* 2 b))
      (let [split-med (nth new-keys (dec b))
            ;; One day, this hardcoded ->IndexNode will cause pain
            left-index (->IndexNode (subvec new-keys 0 (dec b))
                                    (subvec new-children 0 b))
            right-index (->IndexNode (subvec new-keys b)
                                     (subvec new-children b))
            median (nth new-keys (dec b))]
        [left-index median right-index])
      [(factory new-keys
                new-children)])))

(defrecord IndexNode [keys children]
  IResolve
  (resolve [this] this) ;;TODO this is a hack for testing
  IInsert
  (insert
    [node index new-child]
    (let [new-children (assoc children index new-child)]
      [(->IndexNode keys new-children)]))
  (insert [node index new-child1 median new-child2]
    (handle-multi-insert ->IndexNode node index new-child1 median new-child2))
  INodeLookup
  (lookup [root key]
    (scan-children-array keys key)))

(defrecord RootNode [keys children]
  IInsert
  (insert
    [node index new-child]
    (let [new-children (assoc children index new-child)]
      [(->RootNode keys new-children)]))
  (insert [node index new-child1 median new-child2]
    (handle-multi-insert ->RootNode node index new-child1 median new-child2))
  INodeLookup
  (lookup [root key]
    (scan-children-array keys key)))

(defrecord DataNode [children]
  IResolve
  (resolve [this] this) ;;TODO this is a hack for testing
  IInsert
  (insert
    [node index key]
    ;cases:
    ;1. index > children; append to end of children
    ;2. index within children
    ;2.1. If index is equal, skip
    ;2.2 If unequal, slip-insert
    ;3. possibly split
    ;(println "index" index "(count children)" (count children))
    ;(println "key" key "children" children)
    (assert (<= 0 index (count children)) "index have a value out of the defined meaning")
    (let [new-data-children (cond
                              (= index (count children))
                              (conj children key)
                              (= (nth children index) key) ;;TODO this case could be a bypass to avoid making a new datanode
                              children
                              :else
                              (catvec (conj (subvec children 0 index)
                                            key)
                                      (subvec children index)))]
      (if (>= (count new-data-children) (* 2 b))
        [(->DataNode (subvec new-data-children 0 b))
         (nth new-data-children (dec b)) ;; Could change the index leaning by doing (- b 2)
         (->DataNode (subvec new-data-children b))]
        [(->DataNode new-data-children)])))
  (insert [node index new-child1 median new-child2]
    (throw (ex-info "impossible--only for index or root nodes" {}))) 
  INodeLookup
  (lookup [root key]
    (loop [i 0]
      (if (= i (count children))
        i
        (let [result (compare key (nth children i))]
          (if (pos? result)
            (recur (inc i))
            i))))))

(defn backtrack-up-path-until
  "Given a path (starting with root and ending with an index), searches backwards,
   passing each pair of parent & index we just came from to the predicate function.
   When that function returns true, we return the path ending in the index for which
   it was true, or else we return the empty path"
  [path pred]
  (loop [path path]
    (when (seq path)
      (let [from-index (peek path)
            tmp (pop path)
            parent (peek tmp)]
        (if (pred parent from-index)
          path
          (recur (pop tmp)))))))

(defn right-successor
  "Given a node on a path, find's that node's right successor node"
  [path]
  ;(clojure.pprint/pprint path)
  ;TODO this function would benefit from a prefetching hint
  ;     to keep the next several sibs in mem
  (when-let [common-parent-path
             (backtrack-up-path-until
               path
               (fn [parent index]
                 (< (inc index) (count (:children parent)))))]
    (let [next-index (-> common-parent-path peek inc)
          parent (-> common-parent-path pop peek)
          new-sibling (resolve (nth (:children parent) next-index))
          ;; We must get back down to the data node
          sibling-lineage (into []
                                (comp (take-while #(or (instance? IndexNode %)
                                                       (instance? DataNode %)))
                                      (map resolve))
                                (iterate #(-> % :children first) new-sibling))
          path-suffix (-> (interleave sibling-lineage
                                      (repeat 0))
                          (butlast)) ; butlast ensures we end w/ node
          ]
      (-> (pop common-parent-path)
          (conj next-index)
          (into path-suffix)))))

(defn forward-iterator
  "Takes the result of a search and returns an iterator going
   forward over the tree. Does lg(n) backtracking sometimes."
  [path start-index]
  (let [start-node (peek path)]
    (assert (instance? DataNode start-node))
    (let [first-elements (-> start-node
                             :children ; Get the indices of it
                             (subvec start-index)) ; skip to the start-index
          next-elements (lazy-seq
                          (when-let [succ (right-successor (pop path))]
                            (forward-iterator succ 0)))]
      (concat first-elements next-elements))))

(defn lookup-path
  "Given a B-tree and a key, gets a path into the tree"
  [tree key]
  (loop [path [tree] ;alternating node/index/node/index/node... of the search taken
         cur tree ;current search node
         ]
    (if (seq (:children cur))
      (let [index (lookup cur key)
            child (nth (:children cur) index (peek (:children cur))) ;;TODO what are the semantics for exceeding on the right? currently it's trunc to the last element
            path' (conj path index child)]
        (if (instance? DataNode cur) ;are we done?
          path'
          (recur path' (resolve child))))
      nil)))

(defn lookup-key
  "Given a B-tree and a key, gets an iterator into the tree"
  [tree key]
  (peek (lookup-path tree key)))

(defn lookup-fwd-iter
  [tree key]
  (let [path (lookup-path tree key)
        path (pop path)
        index (peek path)
        path (pop path)]
    (when path
      (forward-iterator path index))))

(defn insert-key
  [tree new-key]
  ;; The path's structure will be:
  ;; greater-key / greater-index / data-node / index / index-node / index / root
  ;;
  ;; Our goal is to handle the 3 insertion cases:
  ;; for the data-node, the index-node, and the root
  ;;
  ;; For the data-node, we'll smash together the new children in order
  ;; if it's too big, we split & find a new median
  ;; 
  ;; For the index nodes, we'll see if we have 2 children or 1
  ;; if 1, we'll just fix the child pointer and continue
  ;; if 2, we'll smash together the new children, and if too big, we split & find median
  ;;
  ;; For the root, we'll smash it together a last time; if it's too big, we reroot
  ;; otherwise, we're done
  (let [path (lookup-path tree new-key)]
    ;(println "# insert-key")
    ;(clojure.pprint/pprint tree)
    ;(println "Doing insert with path" (map #(:keys % %) path))
    (if path
      (loop [path (next (rseq path))
             new-elts [new-key]]
        ;(println "path is" path)
        (let [insert-index (first path)
              insert-node (fnext path)
              ;;TODO this apply should be direct dispatch b/c this is slow
              insert-result (apply insert insert-node insert-index new-elts)]
          ;(println "insert result was" insert-result)
          (if (= 2 (count path)) ; we've only got the root node
            (if (= 1 (count insert-result))
              (first insert-result)
              (let [[l m r] insert-result]
                (->RootNode [m] [l r])))
            (recur (nnext path) insert-result))))
      ;; Special case for insert into empty tree, since we can't compute paths yet
      (->RootNode [] [(->DataNode [new-key])]))))

(defn delete-key
  [tree key]
  (let [path (lookup-path tree key)])
  )

(defn empty-b-tree
  []
  (->RootNode [] [(->DataNode [])]))

#_(let [x [1 2 3 4 5]
        i (scan-children-array x 2.5)]
    (println i)
    (concat (take i x) [2.5] (drop i x))
    )

;(println "insert:" (insert (->DataNode [1 2 3 4]) 2 2.5))
