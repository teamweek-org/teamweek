(ns org.teamweek.webapp.endpoint.views
  (:require [clojure.string :as str]
            [ring.util.anti-forgery :refer (anti-forgery-field)]
            [datomic.api :as d]
            [hiccup.page :as page]
            [hiccup.form :as form]))

(defn team-page [req token]
  (let [db (:db req)
        data (d/pull db [:team/name :team/members :team/schedule :team/domain :team/questions]
                     [:team/token token])]
    (page/html5
          [:head
           [:title "Welcome to Teamweek"]
           (page/include-css "/assets/normalize.css/normalize.css"
                             "/css/site.css")]
          [:body
           [:h1 "Welcome " (or (:team/name data)
                               (:team/domain data))]
           [:a {:href "/team/logout"} "Logout"]
           (form/form-to
            [:post "/team/update"]
            (anti-forgery-field)
            (form/label "schedule" "Schedule:")
            (form/text-field "schedule" (:team/schedule data))
            [:br]
            (form/label "members" "Members:")
            (form/text-area "members"
                            (str/join "\n" (map :member/name (:team/members data))))
            [:br]
            (form/label "questions" "Questions:")
            (form/text-area {:readonly true}
                            "questions"
                            (str/join "\n" (map :question/text (:team/questions data))))
            [:br]
            (form/submit-button "Update team settings"))])))
