(ns org.teamweek.webapp.endpoint.search
  (:require [compojure.core :refer :all]
            [ring.util.response :refer :all]
            [datomic.api :as d]))



(defn search-endpoint [config]
  (context "/search" []
   (GET "/" req
     (let [t (get-in req [:params :t])
           q (get-in req [:params :q])
           db (:db req)]
       (if t
         (if-let [eid (ffirst (d/q '[:find ?e :in $ ?t :where [?e :team/token ?t]] db t))]
           (let [team (d/entity db eid)
                 domain (:team/domain team)]
             domain)
           (redirect "/"))
         (redirect "/"))))))
