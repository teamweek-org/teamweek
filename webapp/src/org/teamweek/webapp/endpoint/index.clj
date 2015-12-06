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
           (page/include-css "https://cdnjs.cloudflare.com/ajax/libs/pure/0.6.0/pure-min.css")]
          [:body
           [:div {:class "pure-g"}
            [:div {:class "pure-u-1-3"}]
            [:div {:class "pure-u-1-3"}
             [:div {:style "text-align: center;"}
              [:h1 "Welcome to " [:span "Teamweek"]]]
             [:div
              "<ol>
                <li>Create a new Slack <a href=\"https://my.slack.com/services/new/bot\" target=\"_blank\"><em>Bot User</em></a> and copy the token</li>
                <li>Paste the token below to sign in</li>
               </ol>"]
             [:form {:action "/team" :method "POST" :class "pure-form pure-form-stacked"}
              [:fieldset
               [:legen "Join Teamweek"]
               [:input {:name "token" :placeholder "Slack bot token" :required ""}]
               (anti-forgery-field)
               [:button {:class "pure-button pure-button-primary" :type "submit"} "Sign in"]]]
             [:div
              [:p "Note for " [:a {:href "http://clojurecup.com/" :target "_blank"} "ClojureCup"] " judges, we can invite you to our "
               [:a {:href "https://teamweek-org.slack.com/" :target "_blank"} "Slack team"] " for testing"]]]
            [:div {:class "pure-u-1-3"}]]]))))
