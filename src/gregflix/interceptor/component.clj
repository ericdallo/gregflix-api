(ns gregflix.interceptor.component
  (:require [cemerick.friend :as friend]))

(defn add-auth
  [request]
  (update-in request [:auth] merge
             (or (friend/current-authentication)
                 {})))

(defn add [handler]
  (fn [request]
    (-> request
        add-auth
        handler)))
