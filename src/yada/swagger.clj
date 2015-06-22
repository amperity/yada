;; Copyright © 2015, JUXT LTD.

(ns yada.swagger
  (:require
   [clojure.pprint :refer (pprint)]
   [bidi.bidi :refer (Matched resolve-handler unresolve-handler route-seq succeed)]
   [bidi.ring :refer (Ring)]
   [yada.bidi :refer (resource)]
   [yada.resource :refer (Resource)]
   [yada.mime :as mime]
   [clojure.tools.logging :refer :all]
   [camel-snake-kebab :as csk]
   [cheshire.core :as json]
   [ring.swagger.swagger2 :as rs]
   [schema.core :as s])
  (:import (clojure.lang PersistentVector Keyword)))

(defprotocol SwaggerPath
  (encode [_] "Format route as a swagger path"))

(extend-protocol SwaggerPath
  String
  (encode [s] s)
  PersistentVector
  (encode [v] (apply str (map encode v)))
  Keyword
  (encode [k] (str "{" (name k) "}")))

;; TODO: Now extract the produces section!

(defn- to-path [x]
  (let [swagger (-> x :handler meta :swagger)
        resource-map (-> x :handler :resource-map)
        ]
    [(apply str (map encode (:path x)))
     (merge-with merge swagger
              (into {}
                    (for [[k v] (:parameters resource-map)]
                      [k {:parameters v}]
                      )))]))

(defrecord SwaggerSpec [spec created-at]
  Resource
  (produces [_ ctx] #{"application/json"})
  (produces-charsets [_ ctx] #{"UTF-8"})
  (exists? [_ ctx] true)
  (last-modified [_ ctx] created-at)
  (get-state [_ content-type ctx]
    (assert (= (mime/media-type content-type) "application/json"))
    (infof "type of swagger-json is %s" (type (rs/swagger-json spec)))
    (json/encode (rs/swagger-json spec)))
  (content-length [_ ctx] nil))

(defrecord Swagger [spec routes handler]
  Matched
  (resolve-handler [this m]
    (if (= (:remainder m) (str (or (:base-path spec) "") "/swagger.json"))
      ;; Return this, which satisfies Ring.
      ;; Truncate :remainder to ensure succeed actually succeeds.
      (succeed this (assoc m :remainder ""))
      ;; Otherwise
      (resolve-handler [[(or (:base-path spec) "") routes]]
                       (merge m {::spec spec}))))

  (unresolve-handler [this m]
    (if (= this (:handler m))
      (or (:base-path spec) "")
      (unresolve-handler (:paths spec) m)))

  Ring
  (request [_ req match-context]
    ;; This yada resource has match-context in its lexical scope,
    ;; containing any yada/partial (or bidi/partial) entries.

    (handler
     req)))

(defn swaggered [spec routes]
  (let [spec (merge spec {:paths (into {} (map to-path (route-seq ["" routes])))})]
    (->Swagger spec routes (resource (->SwaggerSpec spec (java.util.Date.))))))
