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
           [:div.pure-g
            [:div.pure-u-1-3]
            [:div.pure-u-1-3
             [:div {:style "text-align: center;"}
              [:h1 "Welcome to " [:span "Teamweek"]]]
             [:div.intro
              "<p>Our aim is to provide an alternative to <em>status update</em> meetings inspired by
               <a href=\"http://blog.idonethis.com/google-snippets-internal-tool/\" target=\"_target\">Google Snippets</a>.
               Every week your team gets asked a couple of simple questions:</p>\n
               <ul>\n<li>What have you achieved this week?</li>\n<li>What do you commit to do next week?</li>\n</ul>
               \n<p>The team replies and those get stored in a database that is searchable by any team member,
                providing a more transparent way of communicating progress. Using hashtags you can filter for the
                most recent updates for those projects you’re interested in.</p>\n<h2>
                <a id=\"Implementation_9\"></a>Implementation</h2>\n<p>The initial implementation is done as
                a <a href=\"https://slack.com/\" target=\"_blank\">Slack</a> bot that interacts with your team members.</p>"]
             [:div
              "<h2>Join us</h2>
               <ol>
                <li>Create a new Slack <a href=\"https://my.slack.com/services/new/bot\" target=\"_blank\"><em>Bot User</em></a> and copy the token</li>
                <li>Paste the token below to sign in</li>
               </ol>"]
             [:form {:action "/team" :method "POST" :class "pure-form pure-form-stacked"}
              [:fieldset
               [:legend "Join Teamweek"]
               [:input {:name "token" :placeholder "Slack bot token" :required ""}]
               (anti-forgery-field)
               [:button {:class "pure-button pure-button-primary" :type "submit"} "Sign in"]]]
             [:div
              [:p "Note for " [:a {:href "http://clojurecup.com/" :target "_blank"} "ClojureCup"] " judges, we can invite you to our "
               [:a {:href "https://teamweek-org.slack.com/" :target "_blank"} "Slack team"] " for testing"]]]
            [:div {:class "pure-u-1-3"}]]]))))
