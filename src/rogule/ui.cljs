(ns rogule.ui
  (:require
    [clojure.set :refer [difference]]
    [reagent.core :as r]
    [reagent.dom :as rdom]
    [sitefox.ui :refer [log]]
    [rogule.emoji :refer [tile tile-mem]]
    [rogule.map :refer [make-digger-map]]
    ["rot-js" :as ROT]
    ["seedrandom" :as seedrandom])
  ;(:require-macros [rogule.loader :refer [load-sprites lookup-twemoji load-sprite]])
  ; (:require-macros [rogule.static :refer [copy-sprites]])
  (:require-macros
    [rogule.loader :refer [load-sprite]]))

(def initial-state
  {:message {:expires 5
             :text "Press ? for help."}})

(defonce state (r/atom initial-state))
(defonce keymap (r/atom {}))

(def size 32)
(def visible-dist 9)
(def visible-dist-sq (js/Math.pow visible-dist 2))
(def clear-dist 7)
(def clear-dist-sq (js/Math.pow clear-dist 2))

#_ (def sprites (load-sprites {:shrine "26E9"
                               :herbs "1F33F"
                               :feather "1FAB6"
                               :bone "1F9B4"
                               :olive-sprig "1FAD2"
                               :egg "1F95A"
                               :grapes "1F347"
                               :meat-on-bone "1F356"
                               :mushroom "1F344"
                               :chestnut "1F330"
                               :hole "1F573"}))

; (log (lookup-twemoji :bone))
; (log (load-sprite :bone))

; (log (prep :bone))

(def forage-items
  [{:name "herbs"
    :sprite (load-sprite :herb)
    :value 1}
   {:name "feather"
    :sprite (load-sprite :feather)
    :value 1}
   {:name "bone"
    :sprite (load-sprite :bone)
    :value 1}
   {:name "olive sprig"
    :sprite (load-sprite :olive)
    :value 1}

   {:name "egg"
    :sprite (load-sprite :egg)
    :value 2}
   {:name "grapes"
    :sprite (load-sprite :grapes)
    :value 2}
   {:name "meat on bone"
    :sprite (load-sprite :meat-on-bone)
    :value 2}

   {:name "mushroom"
    :sprite (load-sprite :mushroom)
    :value 4}
   {:name "chestnut"
    :sprite (load-sprite :chestnut)
    :value 4}

   {:name "gem"
    :sprite (load-sprite :gem-stone)
    :value 8}])

(def item-covers
  [{:sprite (load-sprite :hole)
    :name "hole"}
   {:sprite (load-sprite :rock)
    :name "rock"}
   {:sprite (load-sprite :wood)
    :name "wood block"}])

(def indoor-scenery
  [{:sprite (load-sprite :fountain)
    :name "fountain"}
   {:sprite (load-sprite :potted-plant)
    :name "pot plant"}
   {:sprite (load-sprite :moai)
    :name "statue"}])

(def shrine-template {:sprite (load-sprite :shinto-shrine)
                      :name "shrine"})

(def key-dir-map
  {37 [0 dec]
   72 [0 dec]
   39 [0 inc]
   76 [0 inc]
   38 [1 dec]
   75 [1 dec]
   40 [1 inc]
   74 [1 inc]})

; ***** utility functions ***** ;

(defn entities-by-pos [entities]
  (reduce (fn [es [id e]] (assoc es (conj (:pos e) (:layer e)) (assoc e :id id))) {} entities))

(def entities-by-pos-mem (memoize entities-by-pos))

(defn room-center [room]
  [(int (/ (+ (:_x2 room)
              (:_x1 room))
           2))
   (int (/ (+ (:_y2 room)
              (:_y1 room))
           2))])

(defn distance-sq [[x1 y1] [x2 y2]]
  (+
   (js/Math.pow (- x2 x1) 2) 
   (js/Math.pow (- y2 y1) 2)))

(defn distance [a b]
  (js/Math.sqrt
    (distance-sq a b)))

(defn date-token []
  (let [today (js/Date.)]
    (str (.getFullYear today) "-"
         (inc (.getMonth today)) "-"
         (.getDate today))))

(defn make-id []
  (-> (random-uuid) str (.slice 0 8)))

(defn get-random-entity-by-value [entity-template-table]
  (let [weighted-table (->> entity-template-table
                            (map (fn [i] {(:name i) (/ 1 (:value i))}))
                            (into {})
                            clj->js)
        item-name (ROT/RNG.getWeightedValue weighted-table)]
    (->> entity-template-table
         (filter #(= (:name %) item-name))
         first)))

(defn calculate-max-score [entities]
  (reduce (fn [score [_id e]]
            (let [value (some identity [(:value e) (-> e :drop :value) 0])]
              (+ score value)))
          0 entities))

(defn find-path [[x1 y1] [x2 y2] tiles passable-fn]
  (let [passable-fn-wrapped (partial passable-fn tiles)
        p (ROT/Path.AStar. x1 y1 passable-fn-wrapped)
        path (atom [])]
    (.compute p x2 y2 (fn [x y] (swap! path conj [x y])))
    @path))

; ***** state manipulation functions ***** ;

(defn can-pass-fn [types]
  (fn pass-check
    ([floor-tiles x y] (pass-check floor-tiles [x y]))
    ([floor-tiles pos]
     (let [tile-type (get floor-tiles pos)]
       (contains? (set types) tile-type)))))

(defn remove-entity [*state id]
  (log "remove-entity" id (get-in *state [:entities]))
  (update-in *state [:entities] dissoc id))

(defn add-entity [*state entity]
  (assoc-in *state [:entities (:id entity)] (dissoc entity :id)))

(defn add-to-inventory [*state id item-id entity]
  (update-in *state [:entities id :inventory] conj (assoc entity :id item-id)))

(defn add-message [*state message]
  (assoc *state :message {:text message
                          :expires 3}))

(defn finish-game [*state _their-id _item-id]
  [true (assoc *state :outcome :ascended)])

; ***** item interaction functions ***** ;

(defn add-item-to-inventory [*state their-id item-id]
  (let [them (get-in *state [:entities their-id])
        item (get-in *state [:entities item-id])]
    (if (:inventory them)
      [false (-> *state
                 (add-to-inventory their-id item-id item)
                 (remove-entity item-id)
                 (add-message (str "you found the " (:name item))))]
      [false *state])))

(defn uncover-item [*state _their-id item-id]
  (let [item (get-in *state [:entities item-id])]
    [true (-> *state
              (remove-entity item-id)
              (add-entity (:drop item)))]))

; ***** create different types of things ***** ;

(defn make-player [[entities game-map free-tiles]]
  (let [pos (rand-nth (keys free-tiles))
        player {:sprite (load-sprite :elf)
                :name "you"
                :layer :occupy
                :pos pos
                :stats {}
                :inventory []
                :fns {:update (fn [])
                      :passable (can-pass-fn [:room :door :corridor])}}]
    [(assoc entities :player player)
     (dissoc free-tiles pos)
     game-map]))

(defn make-shrine [[entities free-tiles game-map]]
  (let [player (:player entities)
        pos (rand-nth (keys free-tiles))
        ; gives us a sequence of [room-center-pos path]
        paths-to-rooms (->> (:rooms game-map)
                            (map room-center)
                            (map (fn [room-center-pos]
                                   [room-center-pos
                                    (find-path
                                      (:pos player) room-center-pos
                                      (:floor-tiles game-map)
                                      (-> player :fns :passable))]))
                            (sort-by (juxt second count)))
        furthest-room-center-pos (first (last paths-to-rooms))
        shrine (merge shrine-template
                      {:pos furthest-room-center-pos
                       :layer :occupy
                       :fns {:encounter finish-game}})]
    [(assoc entities :shrine shrine)
     (dissoc free-tiles pos)
     game-map]))

(defn make-covered-item [[entities free-tiles game-map]]
  (let [pos (rand-nth (keys free-tiles))
        item-template (get-random-entity-by-value forage-items)
        item (merge
               item-template
               {:pos pos
                :id (make-id)
                :layer :floor
                :fns {:encounter add-item-to-inventory}})
        cover (merge
                (rand-nth item-covers)
                {:pos pos
                 :layer :floor
                 :drop item
                 :fns {:encounter uncover-item}})]
    [(assoc entities (make-id) cover)
     (dissoc free-tiles pos)
     game-map]))

(defn make-entities [game-map entity-count]
  (let [tiles (:tiles game-map)
        free-tiles (merge
                     (:room tiles)
                     (:corridor tiles))
        entities-free-tiles (make-player [{} game-map free-tiles])
        entities-free-tiles (make-shrine entities-free-tiles)
        [entities] (reduce
                     (fn [entities-free-tiles _i]
                       (make-covered-item entities-free-tiles))
                     entities-free-tiles
                     (range entity-count))]
    entities))

(defn create-level [*state]
  (let [m (make-digger-map (js/Math.random) size size)
        entities (make-entities m 20)
        max-score (calculate-max-score entities)]
    (log "map" m)
    (log "entities" entities)
    (log "max-score" max-score)
    (assoc *state
           :map m
           :entities entities
           :max-score max-score)))

; ***** game engine ***** ;

(defn move-to [*state id new-pos]
  (let [game-map (:map *state)
        floor-tiles (:floor-tiles game-map)
        entity (get-in *state [:entities id])
        passable-fn (-> entity :fns :passable)
        passable-tile? (if passable-fn (passable-fn floor-tiles new-pos) true)
        entities-at-pos (filter (fn [[_id entity]] (= (:pos entity) new-pos)) (:entities *state))
        [item-blocks? state-after-encounters] (reduce (fn [[item-blocks? *state] [entity-id e]]
                                                        (let [encounter-fn (-> e :fns :encounter)]
                                                          (if encounter-fn
                                                            (let [[this-item-blocks? *state] (encounter-fn *state id entity-id)]
                                                              [(or item-blocks? this-item-blocks?) *state])
                                                            [item-blocks? *state])))
                                                      [false *state] entities-at-pos)]
    (if (and passable-tile? (not item-blocks?))
      (assoc-in state-after-encounters [:entities id :pos] new-pos)
      state-after-encounters)))

(defn expire-messages [*state]
  (update-in *state [:message]
             (fn [{:keys [expires text]}]
               (let [display? (not= expires 0)]
                 (when display?
                   {:expires (dec expires)
                    :text text})))))

(defn process-arrow-key! [state ev]
  ; key down -> if not already pressed, push that key onto queue
  ; after a time out
  ;   if any keys are still down duplicate the end of the queue
  (let [code (aget ev "keyCode")
        down? (= (aget ev "type") "keydown")
        dir (get key-dir-map code)]
    (when dir
      (cond (and down?
                 (nil? (-> @keymap :held (get code))))
            (do
              (swap! keymap update-in [:held] (fn [held] (conj (set held) code)))
              (let [dir-idx (first dir)
                    dir-fn (second dir)
                    new-pos (-> @state
                                (get-in [:entities :player :pos])
                                (update-in [dir-idx] dir-fn))]
                (swap! state #(-> %
                                  (move-to :player new-pos)
                                  (expire-messages)))))
            (not down?)
            (swap! keymap update-in [:held] (fn [held] (difference (set held) #{code})))))
    ;(js/console.log "keymap" (clj->js @keymap))
    ))

(defn install-arrow-key-handler [state el]
  (if el
    (let [arrow-handler-fn #(process-arrow-key! state %)]
      (.addEventListener js/window "keydown" arrow-handler-fn)
      (.addEventListener js/window "keyup" arrow-handler-fn)
      (aset js/window "_game-key-handler" arrow-handler-fn))
    (let [arrow-handler-fn (aget js/window "_game-key-handler")]
      (.removeEventListener js/window "keydown" arrow-handler-fn)
      (.removeEventListener js/window "keyup" arrow-handler-fn)
      (js-delete js/window "_game-key-handler"))))

(defn key-handler [ev]
  (let [code (aget ev "keyCode")]
    (print "keyCode" code)
    (case code
      81 (swap! state create-level)
      191 (swap! state update-in [:modal] #(when (not %) :help))
      27 (swap! state dissoc :modal)
      nil)))

; ***** rendering ***** ;

(defn component-cell [floor-tiles entities x y opacity]
  [:span.grid {:key x
               :style {:opacity opacity}}
   (when (> opacity 0)
     (cond 
       (= (get floor-tiles [x y]) :door)
       (tile (load-sprite :white-large-square) "door")
       (= (get floor-tiles [x y]) :room)
       (tile (load-sprite :white-large-square) "room")
       (= (get floor-tiles [x y]) :wall)
       (tile (load-sprite :black-large-square) "wall")
       (= (get floor-tiles [x y]) :corridor)
       (tile (load-sprite :brown-square) "corridor")
       :else nil))
   (for [layer [:floor :occupy]]
     (let [entity (get entities [x y layer])]
       (when entity
         (tile-mem (:sprite entity) (:name entity) {:opacity opacity}))))])

(defn component-inventory [inventory]
  [:div#inventory
   [:div#score (apply + (map :value inventory))]
   [:ul
    (for [e (sort-by (juxt :value :name) inventory)]
      [:li (tile-mem (:sprite e) (:name e) {:width "48px"})])]])

(defn component-help [show-help]
  (when show-help
    [:div.modal
     [:h2 "Rogule"]
     [:p "Find items to obtain the best score."]
     [:p "Get to the shrine " (tile-mem (load-sprite :shinto-shrine) "shrine") " to ascend."]]))

(defn component-messages [message]
  [:div.message message])

(defn component-game [state]
  (let [game-map (:map @state)
        floor-tiles (:floor-tiles game-map)
        entities (entities-by-pos-mem (-> @state :entities))
        player (-> @state :entities :player)
        player-pos (:pos player)
        player-inventory (:inventory player)]
    [:span#game
     [:div {:ref #(install-arrow-key-handler state %)}
      (for [y (range (- (second player-pos) visible-dist)
                     (+ (second player-pos) visible-dist))]
        [:div.row {:key y}
         (for [x (range (- (first player-pos) visible-dist)
                        (+ (first player-pos) visible-dist))]
           (let [dist (distance-sq player-pos [x y])
                 opacity (cond
                           (> dist visible-dist-sq) 0
                           (> dist clear-dist-sq) 0.75
                           :else 1)]
             (component-cell floor-tiles entities x y opacity)))])]
     [component-inventory player-inventory]
     [component-help (= (:modal @state) :help)]
     [component-messages (-> @state :message :text)]]))

(defn copy-element [selector]
  (let [el (.querySelector js/document selector)]
    (->
      (js/navigator.clipboard.writeText (aget el "innerText"))
      (.then (fn [] (js/alert "copied"))))))

(defn component-tombstone [state]
  (let [{:keys [outcome entities]} @state
        {:keys [player]} entities
        {:keys [inventory]} player]
    [:div#tombstone
     [:p "Rogule " (date-token)]
     [:div "Score: " (apply + (map :value inventory)) " / " (:max-score @state)]
     [:p
      (tile (load-sprite :elf) "you") " "
      (name outcome) " "
      (if (= outcome :ascended)
        (tile (load-sprite :glowing-star))
        (tile (load-sprite :skull-and-crossbones)))]
     [:p
      (for [e (sort-by (juxt :value :name) inventory)]
        [:span (tile-mem (:sprite e) (:name e) {:width "48px"})])]
     [:button {:autoFocus true
               :on-click #(reset! state (create-level initial-state))}
      "restart"]
     [:button {:on-click #(copy-element "#tombstone")} "share"]]))

(defn component-main [state]
  (if (:outcome @state)
    [component-tombstone state]
    [component-game state]))

(defn start {:dev/after-load true} []
  (rdom/render [component-main state]
               (js/document.getElementById "app")))

(defn main! []
  (seedrandom (str "Rogule-" (date-token)) #js {:global true})
  (swap! state create-level)
  (.addEventListener js/window "keydown" #(key-handler %))
  (start))
