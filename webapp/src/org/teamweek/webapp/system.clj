(ns org.teamweek.webapp.system
  (:require [clojure.java.io :as io]
            [com.stuartsierra.component :as component]
            [duct.component.endpoint :refer [endpoint-component]]
            [duct.component.handler :refer [handler-component]]
            [duct.middleware.not-found :refer [wrap-not-found]]
            [duct.middleware.route-aliases :refer [wrap-route-aliases]]
            [meta-merge.core :refer [meta-merge]]
            [ring.component.jetty :refer [jetty-server]]
            [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
            [ring.middleware.webjars :refer [wrap-webjars]]
            [org.teamweek.webapp.middleware.datomic :refer [wrap-datomic]]
            [org.teamweek.webapp.endpoint.example :refer [example-endpoint]]
            [org.teamweek.webapp.endpoint.settings :refer [settings-endpoint]]
            [org.teamweek.webapp.endpoint.search :refer [search-endpoint]]))

(def base-config
  {:app {:middleware [[wrap-not-found :not-found]
                      [wrap-webjars]
                      [wrap-defaults :defaults]
                      [wrap-route-aliases :aliases]
                      [wrap-datomic :datomic-uri]]
         :not-found  (io/resource "org/teamweek/webapp/errors/404.html")
         :defaults   (meta-merge site-defaults {:static {:resources "org/teamweek/webapp/public"}})
         :aliases    {"/" "/index.html"}}})

(defn new-system [config]
  (let [config (meta-merge base-config config)]
    (-> (component/system-map
         :app  (handler-component (:app config))
         :http (jetty-server (:http config))
         :example (endpoint-component example-endpoint)
         :settings (endpoint-component settings-endpoint)
         :search (endpoint-component search-endpoint))
        (component/system-using
         {:http [:app]
          :app  [:example :settings :search]
          :example []
          :settings []
          :search []}))))
