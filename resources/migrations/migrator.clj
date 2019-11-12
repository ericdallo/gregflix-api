(ns migrations.migrator
  (:require [clojure.java.io :as io]
            [datomic.api :as d]
            [clj-time.core :as t]
            [clj-time.format :as f]
            [clj-time.coerce :as c]
            [clojure.data.csv :as csv]))

(def tz (t/time-zone-for-id "America/Sao_Paulo"))
(def data-fmt (f/formatter "EEE MMM dd HH:mm:ss yyyy" tz))

(def local-uri "datomic:free://localhost:4334/gregflix?password=123mudar")
(def prod-uri (str "datomic:free://gregflix.site:4334/gregflix?password="))

(defn uuid [] (java.util.UUID/randomUUID))

(defn db [uri] (d/db (d/connect uri)))

(defn schema-names
  []
  (-> "resources/migrations/schema"
      io/file
      .list))

(defn get-schema-from-file
  [schema-name]
  (-> (str "migrations/schema/" schema-name)
      io/resource
      slurp
      read-string))

(defn load-schema-to-db
  [uri schema-name]
  (d/transact (d/connect uri) (get-schema-from-file schema-name)))

(defn load-all-schemas-to-local
  []
  (->> (schema-names)
       (map #(load-schema-to-db local-uri %))))

(defn recreate-db-local-db
  []
  (d/delete-database local-uri)
  (d/create-database local-uri))

(defn get-all-schemas
  [uri]
  (d/q '{:find [?attr ?type ?card]
         :where [[_ :db.install/attribute ?a]
                 [?a :db/valueType ?t]
                 [?a :db/cardinality ?c]
                 [?a :db/ident ?attr]
                 [?t :db/ident ?type]
                 [?c :db/ident ?card]]}
       (db uri)))

(defn safe-non-nil
  [data function]
  (when (and data
             (not (empty? data)))
    (function data)))

(defn remove-keys-if [m pred]
  (apply merge (for [[k v] m :when (not (pred v))] {k v})))

(defn fix-types [m]
  (apply merge (for [[k v] m]

                 (cond
                   (or (= k :db/id)
                       (= k :current-serie/user)
                       (= k :login-audit/user)
                       (= k :related-movie/current-movie)
                       (= k :related-movie/related-movie))
                   {k (Long/valueOf v)}

                   (.contains (name k) "id")
                   {k (uuid)}

                   (.contains (name k) "-at")
                   {k (c/to-date (f/parse data-fmt v))}

                   (or (= k :current-serie/season)
                       (= k :current-serie/episode)
                       (= k :serie/season)
                       (= k :serie/episode))
                   {k (Integer/parseInt v)}
                  
                   :else
                   {k v}))))

(defn fix-csv-data
  [row]
  (-> row
       (remove-keys-if empty?)
       (dissoc :user/updated-at)
       (dissoc :login-audit/user)
       fix-types
       #_clojure.pprint/pprint))

(defn parse-csv [path]
  (let [reader (io/reader path)
        csv-data (csv/read-csv reader)
        data (map zipmap
                  (->> (first csv-data) ;; First row is the header
                       (map keyword) ;; Drop if you want string keys instead
                       repeat)
                  (rest csv-data))]
    (map fix-csv-data data)))

(defn restore
  []
  (let [conn (d/connect local-uri)
        data (parse-csv "resources/migrations/dump.csv")]
    data
    #_@(d/transact conn data)))

(d/q '{:find [?title]
       :where [[_ :serie/id ?title]]}
     (db local-uri))