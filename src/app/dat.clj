(ns app.dat
  (:require
    [datomic.api :as d]
    [clojure.pprint :refer [pprint] :rename {pprint ppr}]
    [clojure.string :as string]
    [clojure.walk :refer [keywordize-keys]]
    [auth0-ring.jwt :refer [get-jwt-verifier verify-token]]
    [app.util :as util :refer [eprn eprintln]]
    )
  (:import [clojure.lang IDeref]))

(defn setup-db []
  (def db-uri (System/getenv "DB_URI"))

  (when (not db-uri)
    (eprintln "Missing DB_URI, exiting")
    (System/exit 1))

  (try
    (if-let [created (d/create-database db-uri)]
      (println "Created database at" db-uri))
    (catch Exception err
      (eprintln "Couldn't check if database exists at" db-uri)))

  (def conn
    (try (d/connect db-uri)
      (catch Exception err
        (eprn err)
        (eprintln "Couldn't reach database at" db-uri)
        (eprintln "Database will be unavailable")
        (Thread/sleep 1)
        (System/exit 1))))

  (declare all-schemas)
  ; Idempotent, repeatable operation
  (d/transact conn all-schemas))


(def db (reify IDeref (deref [_] (d/db conn))))

(defn tx! [tx-data] @(d/transact conn tx-data))

(defn q [query & args] (apply d/q query @db args))

(defn entity-to-dict [entity]
  (when entity (merge {:db/id (:db/id entity)} entity)))

(defn pull-entity [db eid]
  (when-let [entity (d/entity db eid)] (d/touch entity)))

(defn pull-dict [db eid] (entity-to-dict (pull-entity db eid)))

(defn tx-entity! [dict]
  {:pre [(map? dict)]}
  (if (:db/id dict)
    (do (tx! [dict]) (pull-entity @db (:db/id dict)))
    (let [tempid ""
          tx-result (tx! [(assoc dict :db/id tempid)])
          id (d/resolve-tempid @db (:tempids tx-result) tempid)]
      (pull-entity @db id))))

(defn tx-dict! [dict] (entity-to-dict (tx-entity! dict)))


(def user-schema
  [{:db/ident       :user/id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       :user/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :user/nickname
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :user/picture
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :user/email
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :user/email-verified
    :db/valueType   :db.type/boolean
    :db/cardinality :db.cardinality/one}
   ])


(def post-schema
  [{:db/ident       :post/author
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :post/title
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :post/body
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   ])


(def all-schemas (concat
  user-schema
  post-schema
  ))


; https://github.com/Datomic/day-of-datomic/blob/master/tutorial/time-rules.clj
(def time-rules
  '[[(entity-at [?e] ?tx ?t ?inst)
     [?e _ _ ?tx]
     [(datomic.api/tx->t ?tx) ?t]
     [?tx :db/txInstant ?inst]]
    [(touched-at [?e] ?inst)
     [?e _ _ ?tx]
     [?tx :db/txInstant ?inst]]
    [(value-at [?e] ?tx ?t ?inst)
     [_ _ ?e ?tx]
     [(datomic.api/tx->t ?tx) ?t]
     [?tx :db/txInstant ?inst]]
    [(entities-with [?log ?e] ?es)
     [?e _ _ ?tx]
     [(tx-data ?log ?tx) [[?es]]]]])


(def all-posts-q
  '[:find (pull ?e [* {:post/author [:db/id :user/name]}]) (min ?inst)
    :in $ %
    :where
    [?e :post/title]
    (touched-at ?e ?inst)])


(def user-posts-q
  '[:find (pull ?e [* {:post/author [:db/id :user/name]}]) (min ?inst)
    :in $ % ?user
    :where
    [?e :post/title]
    [?e :post/author ?user]
    (touched-at ?e ?inst)])


(def post-q
  '[:find [(pull ?e [* {:post/author [:db/id :user/name]}]) (min ?inst)]
    :in $ % ?e
    :where
    (touched-at ?e ?inst)])


(defn assoc-created-at [[dict inst]] (assoc dict :created-at inst))


(defn get-all-posts [db]
  (->> (d/q all-posts-q db time-rules)
       (map assoc-created-at)
       (sort-by :inst)
       reverse))


(defn get-user-posts [db user-id]
  (->> (d/q user-posts-q db time-rules user-id)
       (map assoc-created-at)
       (sort-by :inst)
       reverse))


(defn get-post [db id]
  (assoc-created-at (d/q post-q db time-rules id)))


(def auth0-config
  {:domain            (System/getenv "AUTH0_DOMAIN")
   :issuer            (System/getenv "AUTH0_ISSUER")
   :client-id         (System/getenv "AUTH0_CLIENT_ID")
   :client-secret     (System/getenv "AUTH0_CLIENT_SECRET")
   :signing-algorithm :hs256
   :scope             "openid user_id name nickname email picture"
   :callback-path     "/auth/callback"
   :error-redirect    "/login"
   :success-redirect  "/"
   :logout-handler    "/auth/logout"
   :logout-redirect   "/"})

(defn jwt-user-to-user-entity [user]
  (util/trim-dict
    {:user/id             (:sub user)
     :user/email          (:email user)
     :user/email-verified (:email_verified user)
     :user/name           (:http://app/name user)
     :user/nickname       (:http://app/nickname user)
     :user/picture        (:http://app/picture user)
     }))

(defn get-user-name [user]
  (or (:user/name user) (:user/nickname user) (:user/id user)))

(def jwt-verifier (get-jwt-verifier auth0-config))

(defn decode-jwt-payload [id-token]
  (util/trim-dict (keywordize-keys (into {} (verify-token jwt-verifier id-token)))))

(def decode-user-from-jwt (comp jwt-user-to-user-entity decode-jwt-payload))

(defn jwt-user-to-db-user [user]
  (when (:sub user) (pull-dict @db [:user/id (:sub user)])))

(defn on-authenticated-write-user-to-db [-user-obj tokens-obj]
  (when-let [id-token (.getIdToken tokens-obj)]
    (when-let [user (decode-user-from-jwt id-token)]
      (tx! [user]))))

(defn rewrite-user [req] (update-in req [:user] jwt-user-to-db-user))

(defn wrap-rewrite-user [handler] (comp handler rewrite-user))
