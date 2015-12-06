(ns org.teamweek.webapp.endpoint.search
  (:require [compojure.core :refer :all]
            [ring.util.response :refer :all]
            [datomic.api :as d]
            [clj-http.client :as http]
            [cheshire.core :as json]
            [hiccup.core :refer :all]
            [hiccup.page :as page]
            [clojure.string :as str]))


(defn get-results
  [domain q]
  (if (str/blank? q)
    []
    (let [query {:body (json/encode {:query {:query_string {:analyze_wildcard "true"
                                                                  :query q}}})}]
      (-> (http/get (str "http://localhost:9200/" domain "/_search") query) ;;TODO remove hardcoded ES_URI
          :body
          (json/parse-string true)))))

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
                 result (get-results domain q)]
             (page/html5
               [:head
                [:title "Teamweek - Seach"]
                (page/include-css "https://cdnjs.cloudflare.com/ajax/libs/pure/0.6.0/pure-min.css")]
               [:div.pure-g
                [:div.pure-u-1-3]
                [:div.pure-u-1-3
                 [:form {:action "/search" :method "get" :class "pure-form"}
                  [:fieldset
                   [:legend "Search"]
                   [:input {:type "hidden" :name "t" :value t}]
                   [:input {:name "q" :value q}]
                   [:input {:type "submit" :name "submit" :value "Search" :class "pure-button pure-button-primary"}]]]
                 [:div
                  [:div (str (get-in result [:hits :total]) " result(s)")]
                  (for [hit (get-in result [:hits :hits])]
                    [:div {:style "border: 1px solid CornflowerBlue; padding:10px; margin-top: 10px; background-color: GhostWhite;"} (:text (:_source hit))
                     [:div {:style "text-align: right; font-size: x-small"} (str "by " (:member (:_source hit)) " @" (:created (:_source hit)))]])]]
                [:div.pure-u-1-3]]))
           (redirect "/"))
         (redirect "/"))))))
