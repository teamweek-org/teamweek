(ns org.teamweek.webapp.endpoint.search
  (:require [compojure.core :refer :all]
            [ring.util.response :refer :all]
            [datomic.api :as d]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [hiccup.core :refer :all]))



(defn search-endpoint [config]
  (context "/search" []
   (GET "/" req
     (let [t (get-in req [:params :t])
           q (get-in req [:params :q])
           db (:db req)]
       (if t
         (if-let [eid (ffirst (d/q '[:find ?e :in $ ?t :where [?e :team/token ?t]] db t))]
           (let [team (d/entity db eid)
                 domain (:team/domain team)
                 query {:body (json/encode {:query {:query_string {:analyze_wildcard "true"
                                                                   :query q}}})}
                 result (-> (http/get (str "http://localhost:9200/" domain "/_search") query) ;;TODO remove hardcoded ES_URI
                            :body
                            (json/parse-string true))]

             (html
               [:ul
                (for [hit (get-in result [:hits :hits])]
                  [:li (:text (:_source hit))])]))
           (redirect "/"))
         (redirect "/"))))))
