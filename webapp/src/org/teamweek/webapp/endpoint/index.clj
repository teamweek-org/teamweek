(ns org.teamweek.webapp.endpoint.index
  (:require [compojure.core :refer :all]
            [hiccup.page :as page]
            [hiccup.form :as form]
            [ring.util.anti-forgery :refer (anti-forgery-field)]))

(defn index-endpoint [config]
  (context "/index" []
    (GET "/" []
         (page/html5
          [:head
           [:title "Welcome to Teamweek"]
           (page/include-css "/assets/normalize.css/normalize.css"
                             "/css/site.css")]
          [:body
           [:h1 "Welcome to " [:span "Teamweek"]]
           [:form {:action "/team" :method "POST"}
            (form/text-field "token")
            (anti-forgery-field)
            [:button "Join Teamweek"]]]))))
