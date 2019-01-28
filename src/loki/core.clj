(ns loki.core
  (:require
   [clojure.walk
    :refer [postwalk]
    :as walk]
   [clojure.string :as str]
   [stencil.core :as stencil]
   [sqly.core :as sql]
   [saw.core :as saw]
   [loki.athena :as athena]
   [loki.util :as u]))

(defn list-databases []
  (->> (athena/exec "show databases")
       (map :database_name)))

(defn list-tables [db]
  (->> (athena/exec (str "show tables in " db))
       (map #(keyword (:tab_name %)))))

(defn describe [db tb]
  (let [stmt (athena/exec db (str "show create table " (name tb)))]
    (apply str (interpose "\n" (map :createtab_stmt stmt)))))

(defn- as-col [{:keys [createtab_stmt]}]
  (let [m (str/triml createtab_stmt)]
    (when (str/starts-with? m "`")
      (-> (str/replace m #"`|\)|," "")
          (str/split #" ")))))

(defn schema [db tb]
  (->> (str "show create table " (name tb))
       (athena/exec (name db))
       (map as-col)
       (remove nil?)
       (into (sorted-map))
       (walk/keywordize-keys)
       (into (sorted-map))))

(defn render
  "Replace template placeholders in query with actual values."
  [query values]
  (postwalk (fn [x]
              (if (string? x)
                (do (u/assert! x values)
                    (stencil/render-string x values))
                x))
            query))

(defn render-query
  "Takes a query map with template variables, renders it with
  given values and executes the query"
  [query values]
  (-> (render query values)
      (sql/sql)))

(defn exec
  ([query-str]
   (athena/exec query-str))
  ([db query-str]
   (athena/exec (name db) (format "%s" query-str))))

(defn parse-duration [duration-str]
  (-> (u/parse-duration duration-str)
      (u/duration->from-and-to)))

(defn query [query-map & {:keys [db values overrides duration]
                          :or   {values    {}
                                 overrides {}}}]
  (let [values    (if duration
                    (merge values (parse-duration duration))
                    values)
        overrides (u/compact overrides)
        q         (-> (merge query-map overrides)
                      (render-query values))]
    (if db
      (exec q)
      (exec db q))))

(defn init! [bucket aws-auth]
  (athena/init! bucket aws-auth))
