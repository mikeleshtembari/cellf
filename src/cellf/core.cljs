(ns ^:figwheel-always cellf.core
  (:require [om.core :as om :include-macros true]
            [om.dom :as dom :include-macros true]
            [cljs.core.async :refer [<! >! take! put! alts! chan timeout]]
            [cellf.media :as media]
            [cellf.strings :refer [strings]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))

(enable-console-print!)

(def default-size 3)
(def capture-size 250)
(def tick-ms      150)
(def resize-ms    200)
(def source-url   "https://github.com/dmotz/cellf")
(def home-url     "http://oxism.com")
(def ls-key       "cellf/ok")
(def canvas-ref   "capture-canvas")
(def video-ref    "vid")

(defonce img-cache (atom []))

(defn sq [n]
  (* n n))


(defn t3d [x y]
  (str "translate3d(" x "%," y "%,0)"))


(defn promise-chan []
  (let [c (chan)]
    (take!
     c
     #(go-loop []
        (>! c %)
        (recur)))
    c))


(defn capture-move [{:keys [ctx vid-node canvas-node vid-w vid-h]} cells]
  (.drawImage
   ctx
   vid-node
   (/ (- vid-w vid-h) 2)
   0
   vid-h
   vid-h
   0
   0
   capture-size
   capture-size)
  (let [img-data (.toDataURL canvas-node "image/jpeg" 1)
        img-el   (js/Image.)
        pc       (promise-chan)]
    (swap! img-cache conj pc)
    (doto img-el
      (aset "src" img-data)
      (aset
       "onload"
       (fn []
         (js-delete img-el "onload")
         (put! pc img-el))))
    {:cells cells :image img-data}))


(defn order [grid]
  (->>
   grid
   (sort-by second)
   (map first)))


(defn inversions [grid]
  (let [cells (order grid)]
    (->>
     grid
     count
     range
     (map
      (fn [n]
        (->>
         cells
         (drop n)
         (filter #(< % (nth cells n)))
         count)))
     (reduce +))))


(defn blank-at-row [grid size]
  (js/Math.floor (/ (:empty grid) size)))


(def solvable?
  (memoize
   (fn [grid size]
     (let [even-inversions? (even? (inversions grid))]
       (or
        (and (odd? size) even-inversions?)
        (and
         (even? size)
         (= even-inversions? (odd? (blank-at-row grid size)))))))))


(defn make-cell-list [size]
  (->
   size
   sq
   dec
   range
   vec
   (conj :empty)))


(defn make-cells [size win-state]
  (let [shuffled (zipmap (make-cell-list size) (shuffle (range (sq size))))]
    (if (or
         (= shuffled win-state)
         (not (solvable? shuffled size)))
      (recur size win-state)
      shuffled)))


(defn make-win-state [size]
  (zipmap (make-cell-list size) (range (sq size))))


(defn make-game
  ([app size]
   (make-game app size tick-ms))
  ([app size speed]
   (let [win-state (make-win-state size)
         cells     (make-cells size win-state)]
     {:moves     [(capture-move app cells)]
      :tick      0
      :tick-ms   speed
      :grid-size size
      :cells     cells
      :win-state win-state})))


(def get-cell-xy (juxt mod quot))


(defn get-cell-style [app i]
  (let [size  (:grid-size app)
        pct   (str (/ 100 size) \%)
        px    (/ (:grid-px app) size)
        [x y] (get-cell-xy i size)]
    #js {:transform  (t3d (* 100 x) (* 100 y))
         :width      pct
         :height     pct
         :lineHeight (str px "px")
         :fontSize   (/ px 10)}))


(defn get-bg-transform [{:keys [grid-size vid-ratio vid-offset]} i]
  (if (= i :empty)
    nil
    (let [size  grid-size
          pct   (/ 100 size)
          [x y] (get-cell-xy i size)]
      #js {:height    (str (* size 100) \%)
           :transform (t3d
                       (- (+ (/ (* x pct) vid-ratio) vid-offset))
                       (- (* pct y)))})))


(defn adj? [app i]
  (let [{size :grid-size {emp :empty} :cells} app
        emp' (inc emp)]
    (cond
      (= i (- emp size)) \d
      (= i (+ emp size)) \u
      (and (= i emp') (pos? (mod emp' size))) \l
      (and (= i (dec emp)) (pos? (mod emp size))) \r
      :else false)))


(defn swap-cell [cells n]
  (let [current-idx (cells n)
        empty-idx   (:empty cells)]
    (into
     {}
     (map
      (fn [[k idx]]
        (if (= k :empty)
          [:empty current-idx]
          (if (= k n) [k empty-idx] [k idx])))
      cells))))


(defn move! [{:keys [moves] :as app} n]
  (let [new-layout (swap-cell (:cells app) n)]
    (om/update! app :cells new-layout)
    (om/update! app :moves (conj moves (capture-move app new-layout)))))


(defn new-game! [app size speed]
  (reset! img-cache [])
  (om/update! app (merge app (make-game app size speed))))


(defn set-vid-src [owner stream]
  (aset (om/get-node owner video-ref) "srcObject" stream))


(defn cell [{:keys [stream n idx] :as app} owner]
  (reify
    om/IDidMount
    (did-mount [_]
      (when (not= n :empty)
        (.setAttribute (om/get-node owner video-ref) "playsinline" "")
        (set-vid-src owner stream)))

    om/IDidUpdate
    (did-update [_ prev-props _]
      (when (and (not= n :empty) (not= n (:n prev-props)))
        (set-vid-src owner stream)))

    om/IRender
    (render [_]
      (when (not= n :empty)
        (let [adj (adj? app idx)]
          (dom/div
           #js {:react-key n
                :className (str "cell" (when adj (str " adjacent-" adj)))
                :style     (get-cell-style app idx)
                :onClick   #(when adj (move! app n))}
           (dom/video #js {:autoPlay    true
                           :muted       true
                           :style       (get-bg-transform app n)
                           :ref         video-ref})
           (dom/label nil (inc n))))))))


(defn grid [{:keys [cells grid-px show-nums] :as app}]
  (om/component
   (apply
    dom/div
    #js {:className (str "grid" (when show-nums " show-nums"))
         :style     #js {:width grid-px :height grid-px}}
    (map
     (fn [[n idx]]
       (om/build cell (merge app {:n n :idx idx})))
     cells))))


(defn set-grid-size! [app size]
  {:pre [(integer? size) (> size 1) (< size 10)]}
  (new-game! app size (:tick-ms @app)))


(defn set-tick-ms! [app ms]
  (om/update! app :tick-ms ms))


(defn get-max-grid-px []
  (min (- js/innerWidth (* capture-size 1.2)) js/innerHeight))


(declare playback-ctx)


(defn paint-canvas! [{:keys [moves grid-size]} tick]
  (go
    (let [img (<! (@img-cache tick))
          s   (/ capture-size grid-size)]
      (.fillRect playback-ctx 0 0 capture-size capture-size)
      (doseq [[idx pos] (:cells (moves tick))]
        (if-not (= idx :empty)
          (let [[x1 y1] (get-cell-xy idx grid-size)
                [x2 y2] (get-cell-xy pos grid-size)]
            (.drawImage
             playback-ctx
             img
             (* x1 s)
             (* y1 s)
             s
             s
             (* x2 s)
             (* y2 s)
             s
             s))))
      (.drawImage playback-ctx img 0 capture-size))))


(defonce app-state (atom {}))


(defn raf-step! [c]
  (js/requestAnimationFrame #(put! c true (partial raf-step! c))))


(defonce raf-chan
  (let [c (chan)]
    (raf-step! c)
    c))

(defonce tick-loop (atom nil))

(defn start! []
  (swap! app-state merge (make-game @app-state default-size))
  (when (nil? @tick-loop)
    (reset! tick-loop
            (go-loop [tick 0]
              (<! (timeout (:tick-ms @app-state)))
              (<! raf-chan)
              (let [app        @app-state
                    move-count (count (:moves app))
                    tick       (if (>= tick move-count) 0 tick)]
                (swap! app-state assoc :tick tick)
                (<! (paint-canvas! app tick))
                (if (or (zero? move-count) (= tick (dec move-count)))
                  (recur 0)
                  (recur (inc tick))))))))


(defn get-camera! [skip-howto?]
  (swap! app-state assoc :camera-waiting? true)
  (take!
   (media/get-media)
   (fn [{:keys [status data]}]
     (if (= status :success)
       (let [video       (.createElement js/document "video")
             canvas      (.createElement js/document "canvas")
             e-key       "oncanplay"]
         (doto canvas
           (aset "width"  capture-size)
           (aset "height" capture-size))
         (doto video
           (.setAttribute "autoplay" "")
           (.setAttribute "playsinline" "")
           (.setAttribute "muted" "")
           (aset "style" "position" "absolute")
           (aset "style" "top" "0")
           (aset "style" "left" "0")
           (aset "style" "pointerEvents" "none")
           (aset "style" "opacity" "0")
           (aset
            e-key
            (fn []
              (let [vw (.-videoWidth video)
                    vh (.-videoHeight video)]
                (js-delete video e-key)
                (swap!
                 app-state
                 merge
                 {:stream      data
                  :grid-px     (get-max-grid-px)
                  :vid-node    video
                  :vid-w       vw
                  :vid-h       vh
                  :vid-ratio   (/ vw vh)
                  :vid-offset  (*
                                100
                                (/
                                 (max 1 (.abs js/Math (- vw vh)))
                                 (max 1 (* vw 2))))
                  :canvas-node canvas
                  :ctx         (.getContext canvas "2d")
                  :show-about? (not skip-howto?)
                  :media-error nil})
                (js/setTimeout start! 100)
                (.setItem js/localStorage ls-key "1"))))
           (aset "srcObject" data))
         (.appendChild (.-body js/document) video))
       (do
         (swap! app-state assoc :media-error data :previous-grant? false)
         (.clear js/localStorage))))))


(defn make-gif [app ms]
  (om/update! app :gif-building? true)
  (let [gif    (js/GIF. #js {:workerScript "js/gif.worker.js" :quality 1})
        canvas (.-canvas playback-ctx)]
    (go
      (dotimes [idx (count (:moves app))]
        (<! (paint-canvas! app idx))
        (.addFrame gif canvas #js {:delay ms :copy true}))
      (doto gif
        (.on "finished" (fn [data]
                          (om/update! app
                                      :result-gif
                                      (.createObjectURL js/URL data))
                          (om/update! app :gif-building? false)))
        (.render)))))


(defn modal [{:keys [stream media-error show-about? result-gif cells win-state
                     grid-size tick-ms gif-building? camera-waiting?
                     previous-grant?] :as app}]
  (let [winner?    (and cells (= cells win-state))
        no-stream? (not stream)]
    (dom/div
     #js {:className
          (str "modal"
               (when (or no-stream? media-error show-about? result-gif winner?)
                 " active"))}
     (cond
       media-error
       (dom/div nil
                (dom/h1 nil "!!!")
                (if (= media-error :denied)
                  (:cam-denied strings)
                  (:cam-failed strings))
                (apply dom/button
                       (if camera-waiting?
                         [#js {:className "wait"} "hold on"]
                         [#js {:onClick get-camera!} "try again"]))
                (dom/button
                 #js {:onClick #(js/open source-url)}
                 "view cellf source"))

       no-stream?
       (if previous-grant?
         (dom/div nil "One moment…")
         (dom/div nil
                  (dom/h1 nil "Hi")
                  (dom/p nil (:intro1 strings))
                  (dom/p nil (:intro2 strings))
                  (apply dom/button
                         (if camera-waiting?
                           [#js {:className "wait"} "hold on"]
                           [#js {:onClick get-camera!} "✔ ok"]))))

       result-gif
       (dom/div nil
                (dom/h1 nil "Your Cellf")
                (dom/a
                 #js {:href result-gif
                      :download "cellf.gif"
                      :className "download"}
                 (dom/img #js {:src result-gif}))
                (:gif-result strings)
                (dom/button
                 #js {:onClick #(om/update! app :result-gif nil)}
                 "✔ done"))

       winner?
       (dom/div nil
                (dom/h1 nil "You win!")
                (:win-info strings)
                (apply dom/button
                       (if gif-building?
                         [#js {:className "wait"} "hold on"]
                         [#js {:onClick #(make-gif app tick-ms)}
                          "make gif replay"]))
                (dom/button
                 #js {:onClick #(set-grid-size! app grid-size)}
                 "new game"))

       show-about?
       (dom/div nil
                (dom/h1 nil "How to play")
                (dom/p nil (:how-to1 strings))
                (dom/p nil (:how-to2 strings))

                (dom/h1 nil "About Cellf")
                (dom/p nil
                       (:about1 strings)
                       (dom/a #js {:href source-url} "open source")
                       (:about2 strings)
                       (dom/a #js {:href home-url} "oxism.com")
                       \.)

                (dom/button
                 #js {:onClick #(om/update! app :show-about? false)}
                 "✔ got it"))))))


(om/root
 (fn [{:keys [stream moves tick tick-ms grid-size show-nums gif-building?]
       :as app}
      owner]
   (reify
     om/IDidMount
     (did-mount
       [_]
       (swap! app-state assoc :canvas-node (om/get-node owner canvas-ref))
       (when (.getItem js/localStorage ls-key)
         (swap! app-state assoc :previous-grant? true)
         (get-camera! true))

       (def playback-ctx
         (let [ctx (-> (om/get-node owner "playback") (.getContext "2d"))]
           (aset ctx "fillStyle" "#fff")
           ctx))

       (defonce resize-loop
         (let [resize-chan (chan)]
           (.addEventListener js/window "resize" #(put! resize-chan true))
           (go-loop [open true]
             (when open (<! resize-chan))
             (let [throttle (timeout resize-ms)]
               (if (= throttle (last (alts! [throttle resize-chan])))
                 (do
                   (swap! app-state assoc :grid-px (get-max-grid-px))
                   (recur true))
                 (recur false)))))))

     om/IDidUpdate
     (did-update
       [_ prev-props _]
       (when (and stream (not= (:stream prev-props) stream))
         (set-vid-src owner stream)))

     om/IRender
     (render
       [_]
       (dom/div
        nil
        (dom/canvas #js {:ref canvas-ref :style #js {:display "none"}})
        (modal app)
        (dom/div
         #js {:id        "sidebar"
              :className (when-not stream "hidden")
              :style     #js {:width capture-size}}

         (dom/img #js {:src "img/cellf.svg" :alt "Cellf"})
         (dom/h2 nil "find yourself")
         (dom/canvas #js {:ref    "playback"
                          :width  capture-size
                          :height (* capture-size 2)})

         (when stream
           (dom/div nil
                    (dom/label
                     #js {:className "move-count"}
                     (str (inc tick) \/ (count moves)))

                    (dom/label
                     #js {:htmlFor "show-nums"}
                     "show numbers?")

                    (dom/input
                     #js {:id       "show-nums"
                          :type     "checkbox"
                          :checked  show-nums
                          :onChange #(om/update!
                                      app :show-nums (not show-nums))})

                    (dom/label
                     nil
                     (str "grid size (" grid-size \× grid-size ")")
                     (dom/em nil "(starts new game)"))

                    (dom/input #js {:type     "range"
                                    :value    grid-size
                                    :min      "2"
                                    :max      "9"
                                    :step     "1"
                                    :onChange
                                    #(set-grid-size!
                                      app
                                      (js/parseInt
                                       (.. % -target -value)))})

                    (dom/label nil "playback speed")

                    (dom/input
                     #js {:type     "range"
                          :value    (- tick-ms)
                          :min      "-1000"
                          :max      "-30"
                          :step     "10"
                          :onChange #(set-tick-ms!
                                      app
                                      (- (js/parseInt
                                          (.. % -target -value))))})

                    (apply dom/button
                           (if gif-building?
                             [#js {:className "wait"} "hold on"]
                             [#js {:onClick #(make-gif app tick-ms)}
                              "make gif"]))

                    (dom/button
                     #js {:onClick #(om/update! app :show-about? true)}
                     "help")

                    (dom/p nil
                           (dom/a
                            #js {:href source-url :target "_blank"}
                            "source")
                           (dom/span nil \/)
                           (dom/a
                            #js {:href home-url :target "_blank"}
                            "oxism")))))

        (when stream (om/build grid app))))))
 app-state
 {:target (.getElementById js/document "app")})
